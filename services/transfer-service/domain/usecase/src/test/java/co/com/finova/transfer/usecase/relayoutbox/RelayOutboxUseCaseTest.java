package co.com.finova.transfer.usecase.relayoutbox;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.gateways.TransactionRunner;
import co.com.finova.transfer.model.message.MessagePublishException;
import co.com.finova.transfer.model.message.OutboundMessage;
import co.com.finova.transfer.model.message.gateways.MessagePublisher;
import co.com.finova.transfer.model.outbox.OutboxRetryBackoff;
import co.com.finova.transfer.model.outbox.PendingOutboxMessage;
import co.com.finova.transfer.model.outbox.gateways.OutboxRelayRepository;
import co.com.finova.transfer.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outbox relay (spec §1.4, ADR 002): publish pending rows with confirms, then mark them as published.
 * Pending item 28: a row that fails is retried later with a growing delay, so it never blocks the next rows and
 * is never dropped.
 */
class RelayOutboxUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00.123456Z");

    private final PendingRows outbox = new PendingRows();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final TrackingTransactions transactions = new TrackingTransactions();
    private final InMemoryPorts.CapturedLogs logs = new InMemoryPorts.CapturedLogs();
    private final Logger useCaseLogger = Logger.getLogger(RelayOutboxUseCase.class.getName());
    private final MutableClock clock = new MutableClock(NOW);

    private final RelayOutboxUseCase useCase = new RelayOutboxUseCase(outbox, publisher, transactions, clock,
            new OutboxRetryBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5)));

    @BeforeEach
    void captureLogs() {
        useCaseLogger.addHandler(logs);
    }

    @AfterEach
    void releaseLogs() {
        useCaseLogger.removeHandler(logs);
    }

    @Test
    void publishesPendingRowsInOrderAndMarksThemPublished() {
        PendingOutboxMessage first = outbox.add("wallet.commands", "wallet.debit");
        PendingOutboxMessage second = outbox.add("audit.events", "audit.attempt");
        PendingOutboxMessage third = outbox.add("audit.events", "audit.attempt");

        int published = useCase.relay(100);

        assertThat(published).isEqualTo(3);
        assertThat(publisher.sent).containsExactly(first.toOutbound(), second.toOutbound(), third.toOutbound());
        assertThat(outbox.publishedAt).containsOnlyKeys(first.id(), second.id(), third.id());
        assertThat(outbox.publishedAt.values()).containsOnly(NOW);
    }

    @Test
    void asksForAtMostTheBatchSize() {
        outbox.add("wallet.commands", "wallet.debit");
        outbox.add("wallet.commands", "wallet.debit");
        outbox.add("wallet.commands", "wallet.debit");

        int published = useCase.relay(2);

        assertThat(outbox.requestedLimits).containsExactly(2);
        assertThat(published).isEqualTo(2);
        assertThat(outbox.pendingCount()).isEqualTo(1);
    }

    @Test
    void doesNothingWhenNoRowIsPending() {
        assertThat(useCase.relay(100)).isZero();
        assertThat(publisher.sent).isEmpty();
        assertThat(outbox.publishedAt).isEmpty();
    }

    @Test
    void locksPublishesAndMarksInOneTransaction() {
        outbox.add("wallet.commands", "wallet.debit");
        outbox.add("wallet.commands", "wallet.debit");

        useCase.relay(100);

        assertThat(transactions.count).isEqualTo(1);
        assertThat(outbox.callsOutsideTransaction).isZero();
    }

    @Test
    void stopsAtTheFirstFailedPublishAndKeepsThatRowAndTheNextOnesPending() {
        PendingOutboxMessage first = outbox.add("wallet.commands", "wallet.debit");
        PendingOutboxMessage failing = outbox.add("audit.events", "audit.attempt");
        PendingOutboxMessage after = outbox.add("audit.events", "audit.attempt");
        publisher.failOn(failing.id(), new MessagePublishException("Broker nacked " + failing.id()));

        int published = useCase.relay(100);

        assertThat(published).isEqualTo(1);
        assertThat(publisher.sent).containsExactly(first.toOutbound());
        assertThat(outbox.publishedAt).containsOnlyKeys(first.id());
        assertThat(outbox.isPending(failing.id())).isTrue();
        assertThat(outbox.isPending(after.id())).isTrue();
    }

    @Test
    void treatsAnyPublishErrorAsAFailedPublish() {
        PendingOutboxMessage failing = outbox.add("wallet.commands", "wallet.debit");
        publisher.failOn(failing.id(), new IllegalStateException("connection refused"));

        assertThat(useCase.relay(100)).isZero();
        assertThat(outbox.isPending(failing.id())).isTrue();
    }

    @Test
    void logsTheFailedPublishAsAnErrorWithItsIdsAndTheNextAttempt() {
        PendingOutboxMessage failing = outbox.add("wallet.commands", "wallet.debit");
        publisher.failOn(failing.id(), new MessagePublishException("No broker confirm for " + failing.id()));

        useCase.relay(100);

        assertThat(logs.messages(Level.SEVERE)).singleElement().asString()
                .contains("messageId=" + failing.id())
                .contains("attempt=1")
                .contains("nextAttemptAt=" + NOW.plusSeconds(1))
                .contains("correlationId=" + failing.correlationId())
                .contains("transferId=" + failing.transferId())
                .contains("No broker confirm");
    }

    @Test
    void aFailingRowDoesNotBlockTheNextOnes() {
        PendingOutboxMessage failing = outbox.add("wallet.commands", "wallet.debit");
        PendingOutboxMessage second = outbox.add("audit.events", "audit.attempt");
        PendingOutboxMessage third = outbox.add("audit.events", "audit.attempt");
        publisher.failOn(failing.id(), new MessagePublishException("NOT_FOUND - no exchange"));

        assertThat(useCase.relay(100)).isZero();
        // The next run (100 ms later) no longer sees the failed row until its retry time.
        clock.advance(Duration.ofMillis(100));
        assertThat(useCase.relay(100)).isEqualTo(2);

        assertThat(outbox.publishedAt).containsOnlyKeys(second.id(), third.id());
        assertThat(outbox.isPending(failing.id())).isTrue();
    }

    @Test
    void aFailedRowIsRetriedWithAGrowingDelay() {
        PendingOutboxMessage failing = outbox.add("wallet.commands", "wallet.debit");
        publisher.failOn(failing.id(), new MessagePublishException("NOT_FOUND - no exchange"));

        useCase.relay(100);
        assertThat(outbox.attempts(failing.id())).isEqualTo(1);
        assertThat(outbox.nextAttemptAt(failing.id())).isEqualTo(NOW.plusSeconds(1));

        clock.advance(Duration.ofMillis(999));
        useCase.relay(100);
        assertThat(outbox.attempts(failing.id())).as("not due yet").isEqualTo(1);

        clock.advance(Duration.ofMillis(1));
        useCase.relay(100);
        assertThat(outbox.attempts(failing.id())).isEqualTo(2);
        assertThat(outbox.nextAttemptAt(failing.id())).isEqualTo(NOW.plusSeconds(1 + 2));

        clock.advance(Duration.ofSeconds(2));
        useCase.relay(100);
        assertThat(outbox.attempts(failing.id())).isEqualTo(3);
        assertThat(outbox.nextAttemptAt(failing.id())).isEqualTo(NOW.plusSeconds(1 + 2 + 4));
        assertThat(outbox.lastError(failing.id())).contains("NOT_FOUND - no exchange");
    }

    @Test
    void aRowThatAlwaysFailsIsNeverDropped() {
        PendingOutboxMessage failing = outbox.add("wallet.commands", "wallet.debit");
        publisher.failOn(failing.id(), new MessagePublishException("NOT_FOUND - no exchange"));

        for (int run = 0; run < 20; run++) {
            useCase.relay(100);
            clock.advance(Duration.ofMinutes(5));
        }

        assertThat(outbox.attempts(failing.id())).isEqualTo(20);
        assertThat(outbox.isPending(failing.id())).isTrue();
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    void aRowThatRecoversIsPublishedOnItsNextAttempt() {
        PendingOutboxMessage flaky = outbox.add("wallet.commands", "wallet.debit");
        publisher.failOn(flaky.id(), new MessagePublishException("No broker confirm"));
        useCase.relay(100);

        publisher.recover(flaky.id());
        clock.advance(Duration.ofSeconds(1));

        assertThat(useCase.relay(100)).isEqualTo(1);
        assertThat(outbox.publishedAt).containsOnlyKeys(flaky.id());
    }

    @Test
    void aSuccessfulRunNeverRecordsAFailure() {
        outbox.add("wallet.commands", "wallet.debit");
        outbox.add("audit.events", "audit.attempt");

        useCase.relay(100);

        assertThat(outbox.failures).isZero();
        assertThat(outbox.requestedAt).containsExactly(NOW);
    }

    @Test
    void rejectsANonPositiveBatchSize() {
        assertThatThrownBy(() -> useCase.relay(0)).isInstanceOf(ContractViolationException.class);
        assertThat(transactions.count).isZero();
    }

    /**
     * Pending rows in creation order, like ORDER BY created_at, skipping rows whose retry time has not come
     * (next_attempt_at); checks every call runs inside a transaction.
     */
    private final class PendingRows implements OutboxRelayRepository {
        final List<PendingOutboxMessage> rows = new ArrayList<>();
        final Map<UUID, Instant> publishedAt = new LinkedHashMap<>();
        final List<Integer> requestedLimits = new ArrayList<>();
        final List<Instant> requestedAt = new ArrayList<>();
        final Map<UUID, Integer> attempts = new LinkedHashMap<>();
        final Map<UUID, Instant> nextAttempt = new LinkedHashMap<>();
        final Map<UUID, String> errors = new LinkedHashMap<>();
        int failures;
        int callsOutsideTransaction;

        int attempts(UUID id) {
            return attempts.getOrDefault(id, 0);
        }

        Instant nextAttemptAt(UUID id) {
            return nextAttempt.get(id);
        }

        String lastError(UUID id) {
            return errors.get(id);
        }

        PendingOutboxMessage add(String exchange, String routingKey) {
            UUID id = UUID.randomUUID();
            PendingOutboxMessage row = new PendingOutboxMessage(id, UUID.randomUUID(), UUID.randomUUID(),
                    exchange, routingKey, "{\"messageId\":\"" + id + "\"}");
            rows.add(row);
            return row;
        }

        boolean isPending(UUID id) {
            return rows.stream().anyMatch(row -> row.id().equals(id)) && !publishedAt.containsKey(id);
        }

        long pendingCount() {
            return rows.stream().filter(row -> !publishedAt.containsKey(row.id())).count();
        }

        @Override
        public List<PendingOutboxMessage> lockPending(int limit, Instant now) {
            checkTransaction();
            requestedLimits.add(limit);
            requestedAt.add(now);
            return rows.stream()
                    .filter(row -> !publishedAt.containsKey(row.id()))
                    .filter(row -> !nextAttempt.containsKey(row.id()) || !nextAttempt.get(row.id()).isAfter(now))
                    .map(row -> new PendingOutboxMessage(row.id(), row.correlationId(), row.transferId(),
                            row.exchange(), row.routingKey(), row.json(), attempts(row.id())))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void markPublished(UUID id, Instant at) {
            checkTransaction();
            publishedAt.put(id, at);
        }

        @Override
        public void recordFailure(UUID id, int attemptCount, Instant nextAttemptAt, String error) {
            checkTransaction();
            failures++;
            attempts.put(id, attemptCount);
            nextAttempt.put(id, nextAttemptAt);
            errors.put(id, error);
        }

        private void checkTransaction() {
            if (!transactions.active) {
                callsOutsideTransaction++;
            }
        }
    }

    /** Counts transactions and knows whether one is open. */
    private static final class TrackingTransactions implements TransactionRunner {
        int count;
        boolean active;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            count++;
            active = true;
            try {
                return work.get();
            } finally {
                active = false;
            }
        }
    }

    /** A clock the test moves forward, to reach a row's retry time. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class RecordingPublisher implements MessagePublisher {
        final List<OutboundMessage> sent = new ArrayList<>();
        private final Map<String, RuntimeException> failures = new LinkedHashMap<>();

        void failOn(UUID messageId, RuntimeException error) {
            failures.put(messageId.toString(), error);
        }

        void recover(UUID messageId) {
            failures.remove(messageId.toString());
        }

        @Override
        public void publish(OutboundMessage message) {
            RuntimeException failure = failures.get(message.messageId());
            if (failure != null) {
                throw failure;
            }
            sent.add(message);
        }
    }
}

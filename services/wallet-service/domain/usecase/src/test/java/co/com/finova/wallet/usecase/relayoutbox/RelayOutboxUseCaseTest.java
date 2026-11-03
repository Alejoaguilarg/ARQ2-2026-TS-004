package co.com.finova.wallet.usecase.relayoutbox;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.message.MessagePublishException;
import co.com.finova.wallet.model.message.OutboundMessage;
import co.com.finova.wallet.model.message.gateways.MessagePublisher;
import co.com.finova.wallet.model.outbox.PendingOutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRelayRepository;
import co.com.finova.wallet.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
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

/** Outbox relay (spec §1.4, ADR 002): publish pending rows with confirms, then mark them as published. */
class RelayOutboxUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00.123456Z");

    private final PendingRows outbox = new PendingRows();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final TrackingTransactions transactions = new TrackingTransactions();
    private final InMemoryPorts.CapturedLogs logs = new InMemoryPorts.CapturedLogs();
    private final Logger useCaseLogger = Logger.getLogger(RelayOutboxUseCase.class.getName());

    private final RelayOutboxUseCase useCase = new RelayOutboxUseCase(outbox, publisher, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));

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
        PendingOutboxMessage first = outbox.add("wallet.events", "wallet.debited");
        PendingOutboxMessage second = outbox.add("wallet.events", "wallet.balance-changed");
        PendingOutboxMessage third = outbox.add("audit.events", "audit.attempt");

        int published = useCase.relay(100);

        assertThat(published).isEqualTo(3);
        assertThat(publisher.sent).containsExactly(first.toOutbound(), second.toOutbound(), third.toOutbound());
        assertThat(outbox.publishedAt).containsOnlyKeys(first.id(), second.id(), third.id());
        assertThat(outbox.publishedAt.values()).containsOnly(NOW);
    }

    @Test
    void asksForAtMostTheBatchSize() {
        outbox.add("wallet.events", "wallet.debited");
        outbox.add("wallet.events", "wallet.debited");
        outbox.add("wallet.events", "wallet.debited");

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
        outbox.add("wallet.events", "wallet.debited");
        outbox.add("wallet.events", "wallet.debited");

        useCase.relay(100);

        assertThat(transactions.count).isEqualTo(1);
        assertThat(outbox.callsOutsideTransaction).isZero();
    }

    @Test
    void stopsAtTheFirstFailedPublishAndKeepsThatRowAndTheNextOnesPending() {
        PendingOutboxMessage first = outbox.add("wallet.events", "wallet.debited");
        PendingOutboxMessage failing = outbox.add("wallet.events", "wallet.balance-changed");
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
        PendingOutboxMessage failing = outbox.add("wallet.events", "wallet.debited");
        publisher.failOn(failing.id(), new IllegalStateException("connection refused"));

        assertThat(useCase.relay(100)).isZero();
        assertThat(outbox.isPending(failing.id())).isTrue();
    }

    @Test
    void logsTheFailedPublishWithCorrelationIdAndTransferId() {
        PendingOutboxMessage failing = outbox.add("wallet.events", "wallet.debited");
        publisher.failOn(failing.id(), new MessagePublishException("No broker confirm for " + failing.id()));

        useCase.relay(100);

        assertThat(logs.messages(Level.WARNING)).singleElement().asString()
                .contains("messageId=" + failing.id())
                .contains("correlationId=" + failing.correlationId())
                .contains("transferId=" + failing.transferId())
                .contains("No broker confirm");
    }

    @Test
    void rejectsANonPositiveBatchSize() {
        assertThatThrownBy(() -> useCase.relay(0)).isInstanceOf(ContractViolationException.class);
        assertThat(transactions.count).isZero();
    }

    /** Pending rows in creation order, like ORDER BY created_at; checks every call runs inside a transaction. */
    private final class PendingRows implements OutboxRelayRepository {
        final List<PendingOutboxMessage> rows = new ArrayList<>();
        final Map<UUID, Instant> publishedAt = new LinkedHashMap<>();
        final List<Integer> requestedLimits = new ArrayList<>();
        int callsOutsideTransaction;

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
        public List<PendingOutboxMessage> lockPending(int limit) {
            checkTransaction();
            requestedLimits.add(limit);
            return rows.stream().filter(row -> !publishedAt.containsKey(row.id())).limit(limit).toList();
        }

        @Override
        public void markPublished(UUID id, Instant at) {
            checkTransaction();
            publishedAt.put(id, at);
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

    private static final class RecordingPublisher implements MessagePublisher {
        final List<OutboundMessage> sent = new ArrayList<>();
        private final Map<String, RuntimeException> failures = new LinkedHashMap<>();

        void failOn(UUID messageId, RuntimeException error) {
            failures.put(messageId.toString(), error);
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

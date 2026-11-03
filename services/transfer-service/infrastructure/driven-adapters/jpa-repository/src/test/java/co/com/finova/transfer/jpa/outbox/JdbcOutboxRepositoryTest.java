package co.com.finova.transfer.jpa.outbox;

import co.com.finova.transfer.jpa.support.PostgresTestBase;
import co.com.finova.transfer.messagejson.MessageJsonMapper;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.payload.DebitWallet;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.outbox.OutboxMessage;
import co.com.finova.transfer.model.outbox.PendingOutboxMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §2 OutboxMessage and §4.6 rule 8: rows hold the full envelope; a row can be made pending again. */
class JdbcOutboxRepositoryTest extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final JdbcOutboxRepository repository =
            new JdbcOutboxRepository(JdbcClient.create(jdbc), new MessageJsonMapper());
    private final TransactionTemplate tx =
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    private final UUID correlationId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();

    private OutboxMessage save(Instant at) {
        DebitWallet debit = new DebitWallet(transferId, UUID.randomUUID(), Money.parse("150000.00"), Currency.COP);
        OutboxMessage message = OutboxMessage.of(MessageEnvelope.create(UUID.randomUUID(), correlationId, transferId,
                at, debit), at);
        repository.save(message);
        return message;
    }

    @Test
    void aRowHoldsTheDestinationAndTheWholeEnvelope() throws Exception {
        OutboxMessage message = save(NOW);

        assertThat(jdbc.queryForObject("SELECT exchange || '/' || routing_key FROM outbox_messages WHERE id = ?",
                String.class, message.id())).isEqualTo("wallet.commands/debit");
        assertThat(jdbc.queryForObject("SELECT created_at FROM outbox_messages WHERE id = ?", OffsetDateTime.class,
                message.id()).toInstant()).isEqualTo(NOW);
        JsonNode envelope = new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_messages WHERE id = ?", String.class, message.id()));
        assertThat(envelope.get("messageId").asText()).isEqualTo(message.id().toString());
        assertThat(envelope.get("type").asText()).isEqualTo("DebitWallet");
        assertThat(envelope.get("producer").asText()).isEqualTo("transfer-service");
        assertThat(envelope.get("payload").get("amount").asText()).isEqualTo("150000.00");
    }

    @Test
    void theRelaySeesPendingRowsOldestFirstAndNotOncePublished() {
        OutboxMessage second = save(NOW.plusSeconds(1));
        OutboxMessage first = save(NOW);

        List<PendingOutboxMessage> pending = tx.execute(status -> repository.lockPending(10, NOW.plusSeconds(60)));

        assertThat(pending).extracting(PendingOutboxMessage::id).containsExactly(first.id(), second.id());
        assertThat(pending.get(0).correlationId()).isEqualTo(correlationId);
        assertThat(pending.get(0).transferId()).isEqualTo(transferId);
        assertThat(pending.get(0).exchange()).isEqualTo("wallet.commands");
        assertThat(pending.get(0).routingKey()).isEqualTo("debit");
        assertThat(pending.get(0).json()).contains("\"type\": \"DebitWallet\"");

        tx.executeWithoutResult(status -> repository.markPublished(first.id(), NOW.plusSeconds(2)));

        List<PendingOutboxMessage> left = tx.execute(status -> repository.lockPending(10, NOW.plusSeconds(60)));
        assertThat(left).extracting(PendingOutboxMessage::id).containsExactly(second.id());
    }

    @Test
    void aRepublishedRowIsPendingAgainWithTheSameId_rule8() {
        OutboxMessage message = save(NOW);
        tx.executeWithoutResult(status -> repository.markPublished(message.id(), NOW.plusSeconds(1)));

        repository.republish(message.id());

        List<PendingOutboxMessage> pending = tx.execute(status -> repository.lockPending(10, NOW.plusSeconds(60)));
        assertThat(pending).extracting(PendingOutboxMessage::id).containsExactly(message.id());
    }

    @Test
    void aNewRowHasNoAttemptsAndIsReadyNow() {
        OutboxMessage saved = save(NOW);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempts, next_attempt_at, last_error FROM outbox_messages WHERE id = ?", saved.id());
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(tx.execute(status -> repository.lockPending(10, NOW)).get(0).attempts()).isZero();
    }

    @Test
    void recordFailureStoresTheAttemptTheRetryTimeAndTheError() {
        OutboxMessage failed = save(NOW);

        repository.recordFailure(failed.id(), 3, NOW.plusSeconds(4), "NOT_FOUND - no exchange 'wallet.missing'");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempts, last_error, published_at FROM outbox_messages WHERE id = ?", failed.id());
        assertThat(row.get("attempts")).isEqualTo(3);
        assertThat(row.get("last_error")).isEqualTo("NOT_FOUND - no exchange 'wallet.missing'");
        assertThat(row.get("published_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM outbox_messages WHERE id = ?",
                OffsetDateTime.class, failed.id()).toInstant()).isEqualTo(NOW.plusSeconds(4));
    }

    @Test
    void aRowWaitingToRetryNoLongerBlocksTheOnesAfterIt_H1() {
        OutboxMessage failed = save(NOW);
        OutboxMessage next = save(NOW.plusSeconds(1));
        repository.recordFailure(failed.id(), 2, NOW.plusSeconds(10), "No broker confirm");

        List<PendingOutboxMessage> early = tx.execute(status -> repository.lockPending(10, NOW.plusSeconds(9)));
        assertThat(early).extracting(PendingOutboxMessage::id).containsExactly(next.id());

        List<PendingOutboxMessage> due = tx.execute(status -> repository.lockPending(10, NOW.plusSeconds(10)));
        assertThat(due).extracting(PendingOutboxMessage::id).containsExactly(failed.id(), next.id());
        assertThat(due.get(0).attempts()).isEqualTo(2);
    }

    @Test
    void aSweeperResendDoesNotWaitForAnEarlierRetryTime_rule8() {
        OutboxMessage message = save(NOW);
        repository.recordFailure(message.id(), 5, NOW.plusSeconds(300), "No broker confirm");

        repository.republish(message.id());

        List<PendingOutboxMessage> pending = tx.execute(status -> repository.lockPending(10, NOW));
        assertThat(pending).extracting(PendingOutboxMessage::id).containsExactly(message.id());
    }

    @Test
    void recordFailureKeepsOnlyTheFirst500CharactersOfTheErrorAndAcceptsNone() {
        OutboxMessage failed = save(NOW);
        OutboxMessage silent = save(NOW);

        repository.recordFailure(failed.id(), 1, NOW.plusSeconds(1), "x".repeat(800));
        repository.recordFailure(silent.id(), 1, NOW.plusSeconds(1), null);

        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_messages WHERE id = ?", String.class,
                failed.id())).hasSize(500);
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_messages WHERE id = ?", Integer.class,
                silent.id())).isEqualTo(1);
    }

    @Test
    void anUnknownRowCannotBeMarkedOrRepublished() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> repository.republish(unknown)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markPublished(unknown, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.recordFailure(unknown, 1, NOW, "error"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoRelaysNeverLockTheSameRow() {
        save(NOW);
        save(NOW.plusSeconds(1));

        List<PendingOutboxMessage> both = tx.execute(status -> {
            List<PendingOutboxMessage> mine = repository.lockPending(1, NOW.plusSeconds(60));
            List<PendingOutboxMessage> theirs = new TransactionTemplate(
                    new DataSourceTransactionManager(jdbc.getDataSource()),
                    new org.springframework.transaction.support.DefaultTransactionDefinition(
                            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW))
                    .execute(inner -> repository.lockPending(10, NOW.plusSeconds(60)));
            mine.addAll(theirs);
            return mine;
        });

        assertThat(both).extracting(PendingOutboxMessage::id).doesNotHaveDuplicates().hasSize(2);
    }
}

package co.com.finova.wallet.jpa.outbox;

import co.com.finova.wallet.messagejson.MessageJsonMapper;
import co.com.finova.wallet.jpa.support.PostgresTestBase;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.outbox.PendingOutboxMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcOutboxRepositoryTest extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private final JdbcOutboxRepository repository =
            new JdbcOutboxRepository(JdbcClient.create(jdbc), new MessageJsonMapper());

    private final UUID correlationId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();

    @Test
    void savesPendingRowWithDestinationAndFullEnvelope() throws Exception {
        UUID walletId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OutboxMessage message = save(new BalanceChanged(walletId, userId, Money.parse("350000.00"), 4));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT exchange, routing_key, published_at FROM outbox_messages WHERE id = ?", message.id());
        assertThat(row.get("exchange")).isEqualTo("wallet.events");
        assertThat(row.get("routing_key")).isEqualTo("wallet.balance-changed");
        assertThat(row.get("published_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT created_at FROM outbox_messages WHERE id = ?",
                OffsetDateTime.class, message.id()).toInstant()).isEqualTo(NOW);

        JsonNode envelope = payload(message.id());
        assertThat(fieldNames(envelope)).containsExactlyInAnyOrder(
                "messageId", "type", "correlationId", "transferId", "producer", "occurredAt", "payload");
        assertThat(envelope.get("messageId").asText()).isEqualTo(message.id().toString());
        assertThat(envelope.get("type").asText()).isEqualTo("BalanceChanged");
        assertThat(envelope.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(envelope.get("transferId").asText()).isEqualTo(transferId.toString());
        assertThat(envelope.get("producer").asText()).isEqualTo("wallet-service");
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00Z");

        JsonNode payload = envelope.get("payload");
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder("walletId", "userId", "available", "version");
        assertThat(payload.get("available").isTextual()).isTrue();
        assertThat(payload.get("available").asText()).isEqualTo("350000.00");
        assertThat(payload.get("version").asLong()).isEqualTo(4);
    }

    @Test
    void attemptRecordedUsesHttpStatusKeyAndKeepsNulls() throws Exception {
        UUID eventId = UUID.randomUUID();
        AttemptRecorded audit = AttemptRecorded.fromWallet(eventId, AttemptStage.DEBIT, AttemptOutcome.APPLIED,
                null, transferId, UUID.randomUUID(), Money.parse("150000.00"), correlationId, NOW);
        OutboxMessage message = save(eventId, audit);

        JsonNode payload = payload(message.id()).get("payload");

        assertThat(payload.has("httpStatus")).isTrue();
        assertThat(payload.get("httpStatus").isNull()).isTrue();
        assertThat(payload.has("statusCode")).isFalse();
        assertThat(payload.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(payload.get("source").asText()).isEqualTo("WALLET");
        assertThat(payload.get("stage").asText()).isEqualTo("DEBIT");
        assertThat(payload.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(payload.get("reason").isNull()).isTrue();
        assertThat(payload.get("idempotencyKey").isNull()).isTrue();
        assertThat(payload.get("amount").asText()).isEqualTo("150000.00");
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00Z");
    }

    @Test
    void lockPendingReturnsOldestRowsFirstUpToTheLimit() {
        OutboxMessage newest = saveAt(NOW.plusSeconds(2), transferId);
        OutboxMessage oldest = saveAt(NOW, transferId);
        OutboxMessage middle = saveAt(NOW.plusSeconds(1), transferId);

        List<PendingOutboxMessage> pending = repository.lockPending(2);

        assertThat(pending).extracting(PendingOutboxMessage::id).containsExactly(oldest.id(), middle.id());
        assertThat(repository.lockPending(10)).extracting(PendingOutboxMessage::id)
                .containsExactly(oldest.id(), middle.id(), newest.id());
    }

    @Test
    void lockPendingCarriesDestinationIdsAndTheStoredEnvelope() throws Exception {
        OutboxMessage saved = saveAt(NOW, transferId);

        PendingOutboxMessage pending = repository.lockPending(1).get(0);

        assertThat(pending.id()).isEqualTo(saved.id());
        assertThat(pending.correlationId()).isEqualTo(correlationId);
        assertThat(pending.transferId()).isEqualTo(transferId);
        assertThat(pending.exchange()).isEqualTo("wallet.events");
        assertThat(pending.routingKey()).isEqualTo("wallet.balance-changed");
        assertThat(new ObjectMapper().readTree(pending.json())).isEqualTo(payload(saved.id()));
    }

    @Test
    void lockPendingReadsANullTransferId() {
        saveAt(NOW, null);

        assertThat(repository.lockPending(1).get(0).transferId()).isNull();
    }

    @Test
    void markPublishedTakesTheRowOutOfThePendingOnes() {
        OutboxMessage published = saveAt(NOW, transferId);
        OutboxMessage stillPending = saveAt(NOW.plusSeconds(1), transferId);

        repository.markPublished(published.id(), NOW.plusMillis(250));

        assertThat(jdbc.queryForObject("SELECT published_at FROM outbox_messages WHERE id = ?",
                OffsetDateTime.class, published.id()).toInstant()).isEqualTo(NOW.plusMillis(250));
        assertThat(repository.lockPending(10)).extracting(PendingOutboxMessage::id)
                .containsExactly(stillPending.id());
    }

    @Test
    void markPublishedFailsForAnUnknownRow() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> repository.markPublished(unknown, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void twoOpenTransactionsNeverLockTheSameRow() throws Exception {
        OutboxMessage first = saveAt(NOW, transferId);
        OutboxMessage second = saveAt(NOW.plusSeconds(1), transferId);
        OutboxMessage third = saveAt(NOW.plusSeconds(2), transferId);

        try (Connection relayA = jdbc.getDataSource().getConnection();
             Connection relayB = jdbc.getDataSource().getConnection()) {
            relayA.setAutoCommit(false);
            relayB.setAutoCommit(false);

            List<PendingOutboxMessage> lockedByA = onConnection(relayA).lockPending(2);
            List<PendingOutboxMessage> lockedByB = onConnection(relayB).lockPending(10);

            assertThat(lockedByA).extracting(PendingOutboxMessage::id).containsExactly(first.id(), second.id());
            assertThat(lockedByB).extracting(PendingOutboxMessage::id).containsExactly(third.id());

            relayA.rollback();
            assertThat(onConnection(relayB).lockPending(10)).extracting(PendingOutboxMessage::id)
                    .containsExactly(first.id(), second.id(), third.id());
            relayB.rollback();
        }
    }

    private JdbcOutboxRepository onConnection(Connection connection) {
        return new JdbcOutboxRepository(JdbcClient.create(new SingleConnectionDataSource(connection, true)),
                new MessageJsonMapper());
    }

    private OutboxMessage saveAt(Instant createdAt, UUID messageTransferId) {
        UUID walletId = UUID.randomUUID();
        OutboxMessage message = OutboxMessage.of(MessageEnvelope.create(UUID.randomUUID(), correlationId,
                messageTransferId, createdAt, new BalanceChanged(walletId, UUID.randomUUID(),
                        Money.parse("10.00"), 1)), createdAt);
        repository.save(message);
        return message;
    }

    private OutboxMessage save(MessagePayload payload) {
        return save(UUID.randomUUID(), payload);
    }

    private OutboxMessage save(UUID messageId, MessagePayload payload) {
        OutboxMessage message = OutboxMessage.of(
                MessageEnvelope.create(messageId, correlationId, transferId, NOW, payload), NOW);
        repository.save(message);
        return message;
    }

    private JsonNode payload(UUID id) throws Exception {
        String json = jdbc.queryForObject("SELECT payload::text FROM outbox_messages WHERE id = ?", String.class, id);
        return new ObjectMapper().readTree(json);
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

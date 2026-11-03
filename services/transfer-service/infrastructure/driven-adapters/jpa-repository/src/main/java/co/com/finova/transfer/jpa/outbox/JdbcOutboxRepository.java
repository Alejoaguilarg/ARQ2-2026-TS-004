package co.com.finova.transfer.jpa.outbox;

import co.com.finova.transfer.messagejson.MessageJsonMapper;
import co.com.finova.transfer.model.outbox.OutboxMessage;
import co.com.finova.transfer.model.outbox.PendingOutboxMessage;
import co.com.finova.transfer.model.outbox.gateways.OutboxRelayRepository;
import co.com.finova.transfer.model.outbox.gateways.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxRepository implements OutboxRepository, OutboxRelayRepository {

    private final JdbcClient jdbc;
    private final MessageJsonMapper json;

    public JdbcOutboxRepository(JdbcClient jdbc, MessageJsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** payload is the full envelope (spec §2 OutboxMessage); published_at starts null (pending). */
    @Override
    public void save(OutboxMessage message) {
        jdbc.sql("""
                        INSERT INTO outbox_messages (id, exchange, routing_key, payload, created_at, published_at)
                        VALUES (:id, :exchange, :routingKey, CAST(:payload AS jsonb), :createdAt, NULL)
                        """)
                .param("id", message.id())
                .param("exchange", message.exchange())
                .param("routingKey", message.routingKey())
                .param("payload", json.write(message.envelope()))
                .param("createdAt", OffsetDateTime.ofInstant(message.createdAt(), ZoneOffset.UTC))
                .update();
    }

    /**
     * Uses the partial index ix_outbox_pending (published_at IS NULL, created_at). FOR UPDATE SKIP LOCKED
     * holds the rows until the relay transaction ends and lets another instance take the next ones.
     * correlationId and transferId are read from the stored envelope; payload::text is published as it is.
     */
    /** §4.6 rule 8: the same row, with the same messageId, goes back to pending. */
    @Override
    public void republish(UUID id) {
        int updated = jdbc.sql("UPDATE outbox_messages SET published_at = NULL WHERE id = :id")
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("outbox row " + id + " not found");
        }
    }

    @Override
    public List<PendingOutboxMessage> lockPending(int limit) {
        return jdbc.sql("""
                        SELECT id, exchange, routing_key, payload::text AS json,
                               payload->>'correlationId' AS correlation_id,
                               payload->>'transferId' AS transfer_id
                        FROM outbox_messages
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> toPending(rs))
                .list();
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        int updated = jdbc.sql("UPDATE outbox_messages SET published_at = :publishedAt WHERE id = :id")
                .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("outbox row " + id + " not found");
        }
    }

    private static PendingOutboxMessage toPending(ResultSet rs) throws SQLException {
        String transferId = rs.getString("transfer_id");
        return new PendingOutboxMessage(
                rs.getObject("id", UUID.class),
                UUID.fromString(rs.getString("correlation_id")),
                transferId == null ? null : UUID.fromString(transferId),
                rs.getString("exchange"),
                rs.getString("routing_key"),
                rs.getString("json"));
    }
}

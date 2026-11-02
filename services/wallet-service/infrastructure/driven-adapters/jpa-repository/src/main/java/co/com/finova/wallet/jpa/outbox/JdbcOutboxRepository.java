package co.com.finova.wallet.jpa.outbox;

import co.com.finova.wallet.jpa.json.MessageJsonMapper;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

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
}

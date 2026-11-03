package co.com.finova.transfer.jpa.processedmessage;

import co.com.finova.transfer.model.processedmessage.gateways.ProcessedMessageRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class JdbcProcessedMessageRepository implements ProcessedMessageRepository {

    private final JdbcClient jdbc;

    public JdbcProcessedMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ON CONFLICT DO NOTHING on the PRIMARY KEY (message_id, consumer): a second delivery inserts nothing and
     * returns false. A concurrent delivery of the same message waits on the row lock until the first
     * transaction ends, so only one of them applies the effect.
     */
    @Override
    public boolean markProcessed(UUID messageId, String consumer, Instant processedAt) {
        return jdbc.sql("""
                        INSERT INTO processed_messages (message_id, consumer, processed_at)
                        VALUES (:messageId, :consumer, :processedAt)
                        ON CONFLICT (message_id, consumer) DO NOTHING
                        """)
                .param("messageId", messageId)
                .param("consumer", consumer)
                .param("processedAt", OffsetDateTime.ofInstant(processedAt, ZoneOffset.UTC))
                .update() == 1;
    }
}

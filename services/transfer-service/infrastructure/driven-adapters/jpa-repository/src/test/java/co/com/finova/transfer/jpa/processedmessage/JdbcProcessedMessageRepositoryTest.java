package co.com.finova.transfer.jpa.processedmessage;

import co.com.finova.transfer.jpa.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §4.1: a consumer handles each messageId once; PRIMARY KEY (message_id, consumer). */
class JdbcProcessedMessageRepositoryTest extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final JdbcProcessedMessageRepository repository = new JdbcProcessedMessageRepository(JdbcClient.create(jdbc));

    @Test
    void theFirstDeliveryIsNewAndTheSecondIsNot() {
        UUID messageId = UUID.randomUUID();

        assertThat(repository.markProcessed(messageId, "transfer-wallet-events", NOW)).isTrue();
        assertThat(repository.markProcessed(messageId, "transfer-wallet-events", NOW.plusSeconds(5))).isFalse();

        assertThat(jdbc.queryForObject("SELECT processed_at FROM processed_messages WHERE message_id = ?",
                OffsetDateTime.class, messageId).toInstant()).isEqualTo(NOW);
    }

    @Test
    void anotherConsumerOfTheSameMessageIsIndependent() {
        UUID messageId = UUID.randomUUID();

        assertThat(repository.markProcessed(messageId, "a", NOW)).isTrue();
        assertThat(repository.markProcessed(messageId, "b", NOW)).isTrue();
    }
}

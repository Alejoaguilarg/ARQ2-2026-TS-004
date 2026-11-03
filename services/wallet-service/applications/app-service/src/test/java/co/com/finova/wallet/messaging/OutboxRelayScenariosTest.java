package co.com.finova.wallet.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pending item 28 with the real relay, PostgreSQL and RabbitMQ: a row that can never be published (its exchange
 * does not exist in the broker) is retried later and never blocks the rows after it, and is never dropped.
 * The test does not declare the missing exchange: the Wallet never declares topology.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OutboxRelayScenariosTest extends MessagingTestBase {

    private static final String MISSING_EXCHANGE = "wallet.missing-exchange";

    @Test
    void aRowToAMissingExchangeDoesNotBlockTheRowsAfterIt(CapturedOutput output) {
        UUID failing = givenOutboxRow(MISSING_EXCHANGE, "wallet.debited", Instant.now().minusSeconds(1));
        UUID healthy = givenOutboxRow("wallet.events", "wallet.debited", Instant.now());

        Message delivered = receive("transfer.wallet-events.q", Duration.ofSeconds(20));

        assertThat(delivered).isNotNull();
        assertThat(delivered.getMessageProperties().getMessageId()).isEqualTo(healthy.toString());
        await("failure recorded", Duration.ofSeconds(10), () -> attempts(failing) >= 1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT published_at, next_attempt_at, last_error FROM outbox_messages WHERE id = ?", failing);
        assertThat(row.get("published_at")).as("never dropped").isNull();
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(Instant.now().minusSeconds(1));
        assertThat((String) row.get("last_error")).isNotBlank();
        assertThat(output.getOut().lines()
                .filter(line -> line.contains(" ERROR ") && line.contains("messageId=" + failing))
                .findFirst()).isPresent();
    }

    private UUID givenOutboxRow(String exchange, String routingKey, Instant createdAt) {
        UUID id = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String payload = """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"1000.00","version":1}"""
                .formatted(transferId, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO outbox_messages (id, exchange, routing_key, payload, created_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """, id, exchange, routingKey,
                envelope(id.toString(), "WalletDebited", UUID.randomUUID(), transferId, "wallet-service", payload),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_messages WHERE id = ?", Integer.class, id);
    }
}

package co.com.finova.transfer.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timeout sweeper over a real broker (spec §4.6 rules 5, 7, 8; F4.3, F4.10, F4.13), with every timeout cut
 * to 1 s: Wallet (played by the test) stays silent, and the transfer still ends REVERSED with nothing lost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "adapters.saga.pending-timeout=1s", "adapters.saga.credit-timeout=1s", "adapters.saga.reverse-resend=1s",
        "adapters.saga.sweep-interval=200ms"})
class SagaTimeoutsMessagingTest extends MessagingTestBase {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate http;

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @Test
    void aSilentWalletEndsInAReversalAndNeverLosesTheMoney_F4_3() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", sender.toString());
        headers.add("X-Correlation-Id", correlationId.toString());
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        String body = http.exchange("/transfers", HttpMethod.POST, new HttpEntity<>("""
                {"recipientUserId":"%s","amount":"80000.00","currency":"COP"}""".formatted(recipient), headers),
                String.class).getBody();
        UUID transferId = UUID.fromString(JSON.readTree(body).get("transferId").asText());

        // Rule 8 / F4.10: no reply to DebitWallet → the same message (same messageId) is published again.
        JsonNode debit = next("wallet.commands.q", WAIT);
        JsonNode republishedDebit = next("wallet.commands.q", WAIT);
        assertThat(republishedDebit.get("type").asText()).isEqualTo("DebitWallet");
        assertThat(republishedDebit.get("messageId").asText()).isEqualTo(debit.get("messageId").asText());

        publishWalletResult("wallet.debited", "WalletDebited", correlationId, transferId, """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"80000.00","version":1}"""
                .formatted(transferId, UUID.randomUUID(), sender));

        // Rule 5 / F4.3: three CreditWallet attempts without reply, then COMPENSATING and ReverseDebit.
        List<Integer> attempts = new ArrayList<>();
        JsonNode command = next("wallet.commands.q", WAIT);
        while (command.get("type").asText().equals("DebitWallet")) {
            command = next("wallet.commands.q", WAIT);
        }
        while (command.get("type").asText().equals("CreditWallet")) {
            attempts.add(command.get("payload").get("attempt").asInt());
            command = next("wallet.commands.q", WAIT);
        }
        assertThat(attempts).containsExactly(1, 2, 3);
        assertThat(command.get("type").asText()).isEqualTo("ReverseDebit");
        assertThat(command.get("payload").get("reason").asText()).isEqualTo("CREDIT_TIMEOUT");
        assertThat(command.get("payload").get("userId").asText()).isEqualTo(sender.toString());

        // Rule 7 / F4.13: the reversal is resent with the same messageId until Wallet answers.
        JsonNode resentReverse = next("wallet.commands.q", WAIT);
        assertThat(resentReverse.get("messageId").asText()).isEqualTo(command.get("messageId").asText());

        publishWalletResult("wallet.debit-reversed", "DebitReversed", correlationId, transferId, """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"80000.00","version":2}"""
                .formatted(transferId, UUID.randomUUID(), sender));
        JsonNode reversed = next("notification.transfer-events.q", WAIT);
        assertThat(reversed.get("type").asText()).isEqualTo("TransferReversed");
        assertThat(reversed.get("payload").get("reason").asText()).isEqualTo("CREDIT_TIMEOUT");
        await("REVERSED", WAIT, () -> "REVERSED".equals(jdbc.queryForObject(
                "SELECT status FROM transfers WHERE transfer_id = ?", String.class, transferId)));

        List<String> audit = jdbc.queryForList("""
                SELECT payload->'payload'->>'stage' || '/' || (payload->'payload'->>'outcome')
                FROM outbox_messages WHERE payload->>'type' = 'AttemptRecorded' ORDER BY created_at""", String.class);
        assertThat(audit).containsExactly("REQUEST/ACCEPTED", "CREDIT/FAILED", "CREDIT/FAILED", "CREDIT/FAILED",
                "TRANSFER/COMPENSATED");
    }
}

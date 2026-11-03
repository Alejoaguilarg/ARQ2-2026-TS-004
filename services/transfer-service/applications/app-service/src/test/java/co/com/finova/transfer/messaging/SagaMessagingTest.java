package co.com.finova.transfer.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The saga over a real broker (spec §4.2, §4.6): the relay publishes what the transfer wrote to its outbox, the
 * listener applies the Wallet results, and the outcome reaches Notification and Audit. The test plays Wallet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SagaMessagingTest extends MessagingTestBase {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate http;

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    private UUID createTransfer() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", sender.toString());
        headers.add("X-Correlation-Id", correlationId.toString());
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> response = http.exchange("/transfers", HttpMethod.POST, new HttpEntity<>("""
                {"recipientUserId":"%s","amount":"150000.00","currency":"COP"}""".formatted(recipient), headers),
                String.class);
        return UUID.fromString(JSON.readTree(response.getBody()).get("transferId").asText());
    }

    private String status(UUID transferId) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE transfer_id = ?", String.class, transferId);
    }

    private String moved(UUID transferId, UUID userId) {
        return """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"150000.00","version":2}"""
                .formatted(transferId, walletId, userId);
    }

    private JsonNode nextAudit() {
        return next("audit.attempts.q", WAIT).get("payload");
    }

    @Test
    void aTransferCompletesThroughTheBroker_F4_1() throws Exception {
        UUID transferId = createTransfer();

        Message debitMessage = receive("wallet.commands.q", WAIT);
        assertThat(debitMessage).as("DebitWallet relayed").isNotNull();
        JsonNode debit = JSON.readTree(debitMessage.getBody());
        assertThat(debit.get("type").asText()).isEqualTo("DebitWallet");
        assertThat(debitMessage.getMessageProperties().getMessageId()).isEqualTo(debit.get("messageId").asText());
        assertThat(debitMessage.getMessageProperties().getReceivedRoutingKey()).isEqualTo("debit");
        assertThat(debit.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(debit.get("payload").get("userId").asText()).isEqualTo(sender.toString());
        assertThat(nextAudit().get("outcome").asText()).isEqualTo("ACCEPTED");

        publishWalletResult("wallet.debited", "WalletDebited", correlationId, transferId, moved(transferId, sender));
        JsonNode credit = next("wallet.commands.q", WAIT);
        assertThat(credit.get("type").asText()).isEqualTo("CreditWallet");
        assertThat(credit.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(credit.get("payload").get("userId").asText()).isEqualTo(recipient.toString());
        assertThat(credit.get("payload").get("attempt").asInt()).isEqualTo(1);
        assertThat(status(transferId)).isEqualTo("DEBITED");

        publishWalletResult("wallet.credited", "WalletCredited", correlationId, transferId,
                moved(transferId, recipient));
        JsonNode completed = next("notification.transfer-events.q", WAIT);
        assertThat(completed.get("type").asText()).isEqualTo("TransferCompleted");
        assertThat(completed.get("payload").get("senderUserId").asText()).isEqualTo(sender.toString());
        assertThat(completed.get("payload").get("recipientUserId").asText()).isEqualTo(recipient.toString());
        assertThat(status(transferId)).isEqualTo("COMPLETED");
        assertThat(nextAudit().get("outcome").asText()).isEqualTo("COMPLETED");

        // A republished WalletCredited (Wallet sends it with a new messageId) changes nothing (rule 9).
        publishWalletResult("wallet.credited", "WalletCredited", correlationId, transferId,
                moved(transferId, recipient));
        await("the repeated result consumed", WAIT, () -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_messages", Integer.class) == 3);
        pause(Duration.ofMillis(500));
        assertThat(messageCount("notification.transfer-events.q")).isZero();
        assertThat(status(transferId)).isEqualTo("COMPLETED");
    }

    @Test
    void aRejectedDebitEndsRejected_F4_2() throws Exception {
        UUID transferId = createTransfer();
        next("wallet.commands.q", WAIT);

        publishWalletResult("wallet.debit-rejected", "DebitRejected", correlationId, transferId, """
                {"transferId":"%s","userId":"%s","reason":"INSUFFICIENT_FUNDS"}""".formatted(transferId, sender));

        JsonNode rejected = next("notification.transfer-events.q", WAIT);
        assertThat(rejected.get("type").asText()).isEqualTo("TransferRejected");
        assertThat(rejected.get("payload").get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(status(transferId)).isEqualTo("REJECTED");
    }

    @Test
    void aMissingRecipientWalletIsCompensated_F4_4() throws Exception {
        UUID transferId = createTransfer();
        next("wallet.commands.q", WAIT);
        publishWalletResult("wallet.debited", "WalletDebited", correlationId, transferId, moved(transferId, sender));
        next("wallet.commands.q", WAIT);

        publishWalletResult("wallet.credit-failed", "CreditFailed", correlationId, transferId, """
                {"transferId":"%s","userId":"%s","attempt":1,"reason":"RECIPIENT_WALLET_NOT_FOUND"}"""
                .formatted(transferId, recipient));
        JsonNode reverse = next("wallet.commands.q", WAIT);
        assertThat(reverse.get("type").asText()).isEqualTo("ReverseDebit");
        assertThat(reverse.get("payload").get("userId").asText()).isEqualTo(sender.toString());
        assertThat(reverse.get("payload").get("reason").asText()).isEqualTo("RECIPIENT_WALLET_NOT_FOUND");

        publishWalletResult("wallet.debit-reversed", "DebitReversed", correlationId, transferId,
                moved(transferId, sender));
        JsonNode reversed = next("notification.transfer-events.q", WAIT);
        assertThat(reversed.get("type").asText()).isEqualTo("TransferReversed");
        assertThat(status(transferId)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM transfers WHERE transfer_id = ?", String.class,
                transferId)).isEqualTo("RECIPIENT_WALLET_NOT_FOUND");
    }

    @Test
    void anUnreadableResultGoesStraightToTheDlq() {
        rabbit.convertAndSend("wallet.events", "wallet.debited", "{\"type\":\"WalletDebited\"}");

        await("the message in the DLQ", WAIT, () -> messageCount("transfer.wallet-events.q.dlq") == 1);
        assertThat(messageCount("transfer.wallet-events.q")).isZero();
    }
}

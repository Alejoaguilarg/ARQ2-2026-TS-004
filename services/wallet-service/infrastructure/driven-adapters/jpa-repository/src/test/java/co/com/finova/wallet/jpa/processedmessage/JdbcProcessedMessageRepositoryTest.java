package co.com.finova.wallet.jpa.processedmessage;

import co.com.finova.wallet.messagejson.MessageJsonMapper;
import co.com.finova.wallet.jpa.support.PostgresTestBase;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.CreditFailed;
import co.com.finova.wallet.model.message.payload.CreditFailureReason;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitReversed;
import co.com.finova.wallet.model.message.payload.WalletCredited;
import co.com.finova.wallet.model.message.payload.DebitRejectionReason;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcProcessedMessageRepositoryTest extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");
    private static final String CONSUMER = "wallet-commands";

    private final JdbcProcessedMessageRepository repository =
            new JdbcProcessedMessageRepository(JdbcClient.create(jdbc), new MessageJsonMapper());

    @Test
    void walletDebitedRoundTrips() throws Exception {
        WalletDebited result = new WalletDebited(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.parse("150000.00"), 4);

        ProcessedMessage read = saveAndRead(result);

        JsonNode stored = storedPayload(read.messageId());
        assertThat(stored.get("amount").isTextual()).isTrue();
        assertThat(stored.get("amount").asText()).isEqualTo("150000.00");
        assertThat(jdbc.queryForObject("SELECT result_type FROM processed_messages WHERE message_id = ?",
                String.class, read.messageId())).isEqualTo("WalletDebited");
    }

    @Test
    void debitRejectedRoundTrips() {
        saveAndRead(new DebitRejected(UUID.randomUUID(), UUID.randomUUID(),
                DebitRejectionReason.SENDER_WALLET_NOT_FOUND));
    }

    @Test
    void creditAndReversalResultsRoundTrip() {
        saveAndRead(new WalletCredited(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.parse("150000.00"), 6));
        saveAndRead(new CreditFailed(UUID.randomUUID(), UUID.randomUUID(), 3, CreditFailureReason.TRANSFER_REVERSED));
        saveAndRead(new DebitReversed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.parse("0.01"), 2));
    }

    @Test
    void findIsEmptyForUnknownMessageOrOtherConsumer() {
        UUID messageId = UUID.randomUUID();
        repository.save(ProcessedMessage.withResult(messageId, CONSUMER, NOW,
                new DebitRejected(UUID.randomUUID(), UUID.randomUUID(), DebitRejectionReason.INSUFFICIENT_FUNDS)));

        assertThat(repository.find(UUID.randomUUID(), CONSUMER)).isEmpty();
        assertThat(repository.find(messageId, "another-consumer")).isEmpty();
    }

    @Test
    void findStoredResultLooksUpByTransferAndResultType() {
        UUID transferId = UUID.randomUUID();
        WalletCredited credited = new WalletCredited(transferId, UUID.randomUUID(), UUID.randomUUID(),
                Money.parse("150000.00"), 6);
        DebitReversed otherTransfer = new DebitReversed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.parse("150000.00"), 2);
        repository.save(ProcessedMessage.withResult(UUID.randomUUID(), CONSUMER, NOW,
                new CreditFailed(transferId, UUID.randomUUID(), 1, CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND)));
        repository.save(ProcessedMessage.withResult(UUID.randomUUID(), CONSUMER, NOW, credited));
        repository.save(ProcessedMessage.withResult(UUID.randomUUID(), CONSUMER, NOW.plusSeconds(5), credited));
        repository.save(ProcessedMessage.withResult(UUID.randomUUID(), CONSUMER, NOW, otherTransfer));

        assertThat(repository.findStoredResult(transferId, MessageType.WALLET_CREDITED)).contains(credited);
        assertThat(repository.findStoredResult(transferId, MessageType.DEBIT_REVERSED)).isEmpty();
        assertThat(repository.findStoredResult(UUID.randomUUID(), MessageType.WALLET_CREDITED)).isEmpty();
    }

    private ProcessedMessage saveAndRead(MessagePayload result) {
        ProcessedMessage message = ProcessedMessage.withResult(UUID.randomUUID(), CONSUMER, NOW, result);

        repository.save(message);

        ProcessedMessage read = repository.find(message.messageId(), CONSUMER).orElseThrow();
        assertThat(read).isEqualTo(message);
        return read;
    }

    private JsonNode storedPayload(UUID messageId) throws Exception {
        String json = jdbc.queryForObject("SELECT result_payload::text FROM processed_messages WHERE message_id = ?",
                String.class, messageId);
        return new ObjectMapper().readTree(json);
    }
}

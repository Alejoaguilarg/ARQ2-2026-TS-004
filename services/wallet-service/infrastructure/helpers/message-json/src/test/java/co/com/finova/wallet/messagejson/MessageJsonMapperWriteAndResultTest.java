package co.com.finova.wallet.messagejson;

import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptSource;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.CreditFailed;
import co.com.finova.wallet.model.message.payload.CreditFailureReason;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitRejectionReason;
import co.com.finova.wallet.model.message.payload.DebitReversed;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.message.payload.WalletCredited;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.money.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON the Wallet writes (outbox envelopes, stored results) and reads back from processed_messages:
 * envelope of spec §4.1, payloads of §4.4, money as a two-decimal string (§1.5), RFC 3339 timestamps.
 */
class MessageJsonMapperWriteAndResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID MESSAGE_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID CORRELATION_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID TRANSFER_ID = UUID.fromString("9b2f6c1e-3d4a-4f5b-8c7d-1e2f3a4b5c6d");
    private static final UUID USER_ID = UUID.fromString("f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b");
    private static final UUID WALLET_ID = UUID.fromString("d2e46189-3076-4f3d-a4f2-cd84ee25e49c");
    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00.123456Z");

    private final MessageJsonMapper mapper = new MessageJsonMapper();

    @Test
    void writesTheEnvelopeWithContractNamesMoneyAsTextAndRfc3339Time() throws Exception {
        WalletDebited debited = new WalletDebited(TRANSFER_ID, WALLET_ID, USER_ID, Money.parse("150000.00"), 3);

        JsonNode envelope = JSON.readTree(mapper.write(
                MessageEnvelope.create(MESSAGE_ID, CORRELATION_ID, TRANSFER_ID, NOW, debited)));

        assertThat(fieldNames(envelope)).containsExactlyInAnyOrder("messageId", "type", "correlationId",
                "transferId", "producer", "occurredAt", "payload");
        assertThat(envelope.get("messageId").asText()).isEqualTo(MESSAGE_ID.toString());
        assertThat(envelope.get("type").asText()).isEqualTo("WalletDebited");
        assertThat(envelope.get("producer").asText()).isEqualTo("wallet-service");
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00.123456Z");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("amount").isTextual()).isTrue();
        assertThat(payload.get("amount").asText()).isEqualTo("150000.00");
        assertThat(payload.get("version").asLong()).isEqualTo(3);
    }

    @Test
    void writesAWalletAttemptWithHttpStatusNullAndEveryField() throws Exception {
        AttemptRecorded attempt = AttemptRecorded.fromWallet(MESSAGE_ID, AttemptStage.DEBIT, AttemptOutcome.REJECTED,
                "INSUFFICIENT_FUNDS", TRANSFER_ID, USER_ID, Money.parse("80000.00"), CORRELATION_ID, NOW);

        JsonNode payload = JSON.readTree(mapper.write(attempt));

        assertThat(fieldNames(payload)).containsExactlyInAnyOrder("eventId", "source", "stage", "outcome", "reason",
                "transferId", "userId", "idempotencyKey", "amount", "httpStatus", "correlationId", "occurredAt");
        assertThat(payload.has("statusCode")).isFalse();
        assertThat(payload.get("httpStatus").isNull()).isTrue();
        assertThat(payload.get("idempotencyKey").isNull()).isTrue();
        assertThat(payload.get("source").asText()).isEqualTo("WALLET");
        assertThat(payload.get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(payload.get("amount").asText()).isEqualTo("80000.00");
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-11-02T15:00:00.123456Z");
    }

    @Test
    void writesAnHttpStatusAndNullOptionalIds() throws Exception {
        AttemptRecorded attempt = new AttemptRecorded(MESSAGE_ID, AttemptSource.GATEWAY, AttemptStage.REQUEST,
                AttemptOutcome.ACCEPTED, null, null, null, "idem-1", null, 202, CORRELATION_ID, NOW);

        JsonNode payload = JSON.readTree(mapper.write(attempt));

        assertThat(payload.get("httpStatus").isInt()).isTrue();
        assertThat(payload.get("httpStatus").asInt()).isEqualTo(202);
        assertThat(payload.get("transferId").isNull()).isTrue();
        assertThat(payload.get("userId").isNull()).isTrue();
        assertThat(payload.get("amount").isNull()).isTrue();
        assertThat(payload.get("reason").isNull()).isTrue();
        assertThat(payload.get("idempotencyKey").asText()).isEqualTo("idem-1");
    }

    @Test
    void failsClearlyWhenAValueCannotBeWritten() {
        assertThatThrownBy(() -> mapper.write(new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Object");
    }

    @Test
    void readsBackEveryStoredResult() {
        List<MessagePayload> results = List.of(
                new WalletDebited(TRANSFER_ID, WALLET_ID, USER_ID, Money.parse("150000.00"), 3),
                new DebitRejected(TRANSFER_ID, USER_ID, DebitRejectionReason.INSUFFICIENT_FUNDS),
                new WalletCredited(TRANSFER_ID, WALLET_ID, USER_ID, Money.parse("0.01"), 9),
                new CreditFailed(TRANSFER_ID, USER_ID, 2, CreditFailureReason.TRANSFER_REVERSED),
                new DebitReversed(TRANSFER_ID, WALLET_ID, USER_ID, Money.parse("150000.00"), 4));

        for (MessagePayload result : results) {
            assertThat(mapper.readResult(result.type(), mapper.write(result))).isEqualTo(result);
        }
    }

    @Test
    void readResultRejectsATypeThatIsNotAWalletResult() {
        assertThatThrownBy(() -> mapper.readResult(MessageType.BALANCE_CHANGED, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BalanceChanged");
    }

    @Test
    void readResultFailsClearlyOnACorruptStoredResult() {
        assertThatThrownBy(() -> mapper.readResult(MessageType.WALLET_DEBITED, "{\"transferId\":"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WalletDebited");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

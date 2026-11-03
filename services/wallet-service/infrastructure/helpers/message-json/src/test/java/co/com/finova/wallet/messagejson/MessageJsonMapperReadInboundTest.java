package co.com.finova.wallet.messagejson;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.CreditWallet;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.ReverseDebit;
import co.com.finova.wallet.model.message.payload.ReverseReason;
import co.com.finova.wallet.model.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading of the messages the Wallet consumes (spec §4.1 envelope, §4.4 payloads). Every invalid input
 * must fail with ContractViolationException, which the consumer treats as non-retryable.
 */
class MessageJsonMapperReadInboundTest {

    private static final String MESSAGE_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String CORRELATION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String TRANSFER_ID = "9b2f6c1e-3d4a-4f5b-8c7d-1e2f3a4b5c6d";
    private static final String USER_ID = "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b";
    private static final String WALLET_ID = "d2e46189-3076-4f3d-a4f2-cd84ee25e49c";

    private final MessageJsonMapper mapper = new MessageJsonMapper();

    @Test
    void readsDebitWalletWithItsEnvelope() {
        MessageEnvelope<?> envelope = mapper.readInbound(envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"%s","amount":"150000.00","currency":"COP"}"""
                .formatted(TRANSFER_ID, USER_ID)));

        assertThat(envelope.messageId()).isEqualTo(UUID.fromString(MESSAGE_ID));
        assertThat(envelope.type()).isEqualTo(MessageType.DEBIT_WALLET);
        assertThat(envelope.correlationId()).isEqualTo(UUID.fromString(CORRELATION_ID));
        assertThat(envelope.transferId()).isEqualTo(UUID.fromString(TRANSFER_ID));
        assertThat(envelope.producer()).isEqualTo("transfer-service");
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-10-19T15:00:00Z"));
        assertThat(envelope.payload()).isEqualTo(new DebitWallet(UUID.fromString(TRANSFER_ID),
                UUID.fromString(USER_ID), Money.parse("150000.00"), Currency.COP));
    }

    @Test
    void readsCreditWallet() {
        MessageEnvelope<?> envelope = mapper.readInbound(envelope("CreditWallet", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"%s","amount":"0.01","currency":"COP","attempt":3}"""
                .formatted(TRANSFER_ID, USER_ID)));

        assertThat(envelope.payload()).isEqualTo(new CreditWallet(UUID.fromString(TRANSFER_ID),
                UUID.fromString(USER_ID), Money.parse("0.01"), Currency.COP, 3));
    }

    @Test
    void readsReverseDebit() {
        MessageEnvelope<?> envelope = mapper.readInbound(envelope("ReverseDebit", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"%s","amount":"150000.00","currency":"COP","reason":"CREDIT_TIMEOUT"}"""
                .formatted(TRANSFER_ID, USER_ID)));

        assertThat(envelope.payload()).isEqualTo(new ReverseDebit(UUID.fromString(TRANSFER_ID),
                UUID.fromString(USER_ID), Money.parse("150000.00"), Currency.COP, ReverseReason.CREDIT_TIMEOUT));
    }

    @Test
    void readsBalanceChangedWithZeroBalanceAndNullTransferId() {
        MessageEnvelope<?> envelope = mapper.readInbound(envelope("BalanceChanged", "null", """
                {"walletId":"%s","userId":"%s","available":"0.00","version":7}"""
                .formatted(WALLET_ID, USER_ID)));

        assertThat(envelope.transferId()).isNull();
        assertThat(envelope.payload()).isEqualTo(new BalanceChanged(UUID.fromString(WALLET_ID),
                UUID.fromString(USER_ID), Money.parse("0.00"), 7));
    }

    @Test
    void readsCommandWithoutEnvelopeTransferId() {
        MessageEnvelope<?> envelope = mapper.readInbound(envelope("DebitWallet", "null", debitPayload("150000.00")));

        assertThat(envelope.transferId()).isNull();
        assertThat(((DebitWallet) envelope.payload()).transferId()).isEqualTo(UUID.fromString(TRANSFER_ID));
    }

    @Test
    void ignoresUnknownFieldsInEnvelopeAndPayload() {
        String json = """
                {"messageId":"%s","type":"DebitWallet","correlationId":"%s","transferId":"%s",
                 "producer":"transfer-service","occurredAt":"2026-10-19T15:00:00Z","schemaVersion":2,
                 "payload":{"transferId":"%s","userId":"%s","amount":"150000.00","currency":"COP","note":"rent"}}"""
                .formatted(MESSAGE_ID, CORRELATION_ID, TRANSFER_ID, TRANSFER_ID, USER_ID);

        assertThat(mapper.readInbound(json).payload()).isInstanceOf(DebitWallet.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertInvalid("{\"messageId\":");
    }

    @Test
    void rejectsJsonThatIsNotAnObject() {
        assertInvalid("[]");
    }

    @Test
    void rejectsUnknownType() {
        assertInvalid(envelope("PayWallet", "\"" + TRANSFER_ID + "\"", debitPayload("150000.00")));
    }

    @Test
    void rejectsTypeTheWalletDoesNotConsume() {
        assertInvalid(envelope("WalletDebited", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"150000.00","version":1}"""
                .formatted(TRANSFER_ID, WALLET_ID, USER_ID)));
    }

    @Test
    void rejectsMissingEnvelopeField() {
        String json = envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", debitPayload("150000.00"))
                .replace("\"messageId\":\"" + MESSAGE_ID + "\",", "");
        assertInvalid(json);
    }

    @Test
    void rejectsMissingPayload() {
        String json = """
                {"messageId":"%s","type":"DebitWallet","correlationId":"%s","transferId":"%s",
                 "producer":"transfer-service","occurredAt":"2026-10-19T15:00:00Z"}"""
                .formatted(MESSAGE_ID, CORRELATION_ID, TRANSFER_ID);
        assertInvalid(json);
    }

    @Test
    void rejectsMissingPayloadField() {
        assertInvalid(envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","amount":"150000.00","currency":"COP"}""".formatted(TRANSFER_ID)));
    }

    @Test
    void rejectsInvalidUuid() {
        assertInvalid(envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"not-a-uuid","amount":"150000.00","currency":"COP"}"""
                .formatted(TRANSFER_ID)));
    }

    @Test
    void rejectsInvalidOccurredAt() {
        String json = envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", debitPayload("150000.00"))
                .replace("2026-10-19T15:00:00Z", "yesterday");
        assertInvalid(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"150000.001", "150000", "-1.00", "0.00", "1e5"})
    void rejectsAmountOutsideTheContractFormat(String amount) {
        assertInvalid(envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", debitPayload(amount)));
    }

    @Test
    void rejectsAmountSentAsJsonNumber() {
        assertInvalid(envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"%s","amount":150000.00,"currency":"COP"}"""
                .formatted(TRANSFER_ID, USER_ID)));
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertInvalid(envelope("DebitWallet", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"%s","amount":"150000.00","currency":"USD"}"""
                .formatted(TRANSFER_ID, USER_ID)));
    }

    @Test
    void rejectsCreditAttemptOutOfRange() {
        assertInvalid(envelope("CreditWallet", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"%s","amount":"150000.00","currency":"COP","attempt":4}"""
                .formatted(TRANSFER_ID, USER_ID)));
    }

    @Test
    void rejectsUnknownReverseReason() {
        assertInvalid(envelope("ReverseDebit", "\"" + TRANSFER_ID + "\"", """
                {"transferId":"%s","userId":"%s","amount":"150000.00","currency":"COP","reason":"BECAUSE"}"""
                .formatted(TRANSFER_ID, USER_ID)));
    }

    @Test
    void rejectsEnvelopeTransferIdDifferentFromPayload() {
        String otherTransferId = "11111111-2222-4333-8444-555555555555";
        assertInvalid(envelope("DebitWallet", "\"" + otherTransferId + "\"", debitPayload("150000.00")));
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> mapper.readInbound(json)).isInstanceOf(ContractViolationException.class);
    }

    private static String debitPayload(String amount) {
        return """
                {"transferId":"%s","userId":"%s","amount":"%s","currency":"COP"}"""
                .formatted(TRANSFER_ID, USER_ID, amount);
    }

    private static String envelope(String type, String transferIdJson, String payloadJson) {
        return """
                {"messageId":"%s","type":"%s","correlationId":"%s","transferId":%s,
                 "producer":"transfer-service","occurredAt":"2026-10-19T15:00:00Z","payload":%s}"""
                .formatted(MESSAGE_ID, type, CORRELATION_ID, transferIdJson, payloadJson);
    }
}

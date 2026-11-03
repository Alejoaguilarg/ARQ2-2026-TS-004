package co.com.finova.transfer.messagejson;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptRecorded;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.CreditFailed;
import co.com.finova.transfer.model.message.payload.CreditFailureReason;
import co.com.finova.transfer.model.message.payload.CreditWallet;
import co.com.finova.transfer.model.message.payload.DebitRejected;
import co.com.finova.transfer.model.message.payload.DebitRejectionReason;
import co.com.finova.transfer.model.message.payload.DebitReversed;
import co.com.finova.transfer.model.message.payload.TransferReversed;
import co.com.finova.transfer.model.message.payload.ReverseReason;
import co.com.finova.transfer.model.message.payload.WalletCredited;
import co.com.finova.transfer.model.message.payload.WalletDebited;
import co.com.finova.transfer.model.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §4.1 and §4.4: the Wallet results as Wallet writes them, and the messages Transfer writes. */
class MessageJsonMapperTest {

    private static final String TRANSFER_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String WALLET_ID = "11111111-2222-4333-8444-555555555555";
    private static final String USER_ID = "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b";
    private static final String CORRELATION_ID = "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11";
    private static final String MESSAGE_ID = "9f8e7d6c-5b4a-4321-8fed-cba987654321";

    private final MessageJsonMapper json = new MessageJsonMapper();

    private static String envelope(String type, String payload) {
        return """
                {"messageId":"%s","type":"%s","correlationId":"%s","transferId":"%s",
                 "producer":"wallet-service","occurredAt":"2026-10-19T15:00:00.210Z","payload":%s}
                """.formatted(MESSAGE_ID, type, CORRELATION_ID, TRANSFER_ID, payload);
    }

    @Test
    void readsWalletDebited() {
        MessageEnvelope<?> read = json.readInbound(envelope("WalletDebited", """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"150000.00","version":7}
                """.formatted(TRANSFER_ID, WALLET_ID, USER_ID)));

        assertThat(read.messageId()).isEqualTo(UUID.fromString(MESSAGE_ID));
        assertThat(read.type()).isEqualTo(MessageType.WALLET_DEBITED);
        assertThat(read.correlationId()).isEqualTo(UUID.fromString(CORRELATION_ID));
        assertThat(read.transferId()).isEqualTo(UUID.fromString(TRANSFER_ID));
        assertThat(read.producer()).isEqualTo("wallet-service");
        assertThat(read.occurredAt()).isEqualTo(Instant.parse("2026-10-19T15:00:00.210Z"));
        assertThat(read.payload()).isEqualTo(new WalletDebited(UUID.fromString(TRANSFER_ID),
                UUID.fromString(WALLET_ID), UUID.fromString(USER_ID), Money.parse("150000.00"), 7));
    }

    @Test
    void readsTheOtherWalletResults() {
        UUID transferId = UUID.fromString(TRANSFER_ID);
        UUID walletId = UUID.fromString(WALLET_ID);
        UUID userId = UUID.fromString(USER_ID);
        Money amount = Money.parse("150000.00");
        String moved = """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"150000.00","version":8}
                """.formatted(TRANSFER_ID, WALLET_ID, USER_ID);

        assertThat(json.readInbound(envelope("WalletCredited", moved)).payload())
                .isEqualTo(new WalletCredited(transferId, walletId, userId, amount, 8));
        assertThat(json.readInbound(envelope("DebitReversed", moved)).payload())
                .isEqualTo(new DebitReversed(transferId, walletId, userId, amount, 8));
        assertThat(json.readInbound(envelope("DebitRejected", """
                {"transferId":"%s","userId":"%s","reason":"INSUFFICIENT_FUNDS"}""".formatted(TRANSFER_ID, USER_ID)))
                .payload()).isEqualTo(new DebitRejected(transferId, userId, DebitRejectionReason.INSUFFICIENT_FUNDS));
        assertThat(json.readInbound(envelope("CreditFailed", """
                {"transferId":"%s","userId":"%s","attempt":2,"reason":"TRANSFER_REVERSED","extra":1}"""
                .formatted(TRANSFER_ID, USER_ID))).payload())
                .isEqualTo(new CreditFailed(transferId, userId, 2, CreditFailureReason.TRANSFER_REVERSED));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "not json",
            "{\"type\":\"WalletDebited\"}",
            "{\"type\":\"DebitWallet\",\"payload\":{}}",
            "{\"type\":\"Unknown\",\"payload\":{}}"
    })
    void rejectsMalformedMessages(String body) {
        assertThatThrownBy(() -> json.readInbound(body)).isInstanceOf(ContractViolationException.class);
    }

    @Test
    void rejectsInvalidEnvelopeAndPayloadFields() {
        String debited = """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"150000.00","version":7}
                """.formatted(TRANSFER_ID, WALLET_ID, USER_ID);
        assertThatThrownBy(() -> json.readInbound(envelope("WalletDebited", debited)
                .replace(CORRELATION_ID, "not-a-uuid"))).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> json.readInbound(envelope("WalletDebited", debited)
                .replace("2026-10-19T15:00:00.210Z", "yesterday"))).hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> json.readInbound(envelope("WalletDebited", debited.replace("\"150000.00\"", "150000"))))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> json.readInbound(envelope("WalletDebited", debited.replace(",\"version\":7", ""))))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> json.readInbound(envelope("WalletDebited", "[]")))
                .hasMessageContaining("payload");
        String otherTransfer = envelope("WalletDebited", debited).replaceFirst(TRANSFER_ID,
                "00000000-0000-4000-8000-000000000000");
        assertThatThrownBy(() -> json.readInbound(otherTransfer)).hasMessageContaining("does not match");
    }

    @Test
    void anEnvelopeWithoutTransferIdIsAccepted() {
        String body = envelope("DebitRejected", """
                {"transferId":"%s","userId":"%s","reason":"SENDER_WALLET_NOT_FOUND"}""".formatted(TRANSFER_ID, USER_ID))
                .replace("\"transferId\":\"" + TRANSFER_ID + "\",\n \"producer\"", "\"transferId\":null,\n \"producer\"");

        assertThat(json.readInbound(body).transferId()).isNull();
    }

    @Test
    void writesACommandEnvelopeWithMoneyAsTextAndUtcTimestamps() {
        UUID transferId = UUID.fromString(TRANSFER_ID);
        MessageEnvelope<CreditWallet> envelope = MessageEnvelope.create(UUID.fromString(MESSAGE_ID),
                UUID.fromString(CORRELATION_ID), transferId, Instant.parse("2026-10-19T15:00:00Z"),
                new CreditWallet(transferId, UUID.fromString(USER_ID), Money.parse("150000.00"), Currency.COP, 2));

        assertThat(json.write(envelope)).isEqualTo("{\"messageId\":\"" + MESSAGE_ID + "\",\"type\":\"CreditWallet\","
                + "\"correlationId\":\"" + CORRELATION_ID + "\",\"transferId\":\"" + TRANSFER_ID + "\","
                + "\"producer\":\"transfer-service\",\"occurredAt\":\"2026-10-19T15:00:00Z\","
                + "\"payload\":{\"transferId\":\"" + TRANSFER_ID + "\",\"userId\":\"" + USER_ID + "\","
                + "\"amount\":\"150000.00\",\"currency\":\"COP\",\"attempt\":2}}");
    }

    @Test
    void writesAnOutcomeAndAnAttemptAsTheContractNamesThem() {
        UUID transferId = UUID.fromString(TRANSFER_ID);
        String reversed = json.write(new TransferReversed(transferId, UUID.fromString(USER_ID),
                UUID.fromString(WALLET_ID), Money.parse("5.00"), Currency.COP, ReverseReason.CREDIT_TIMEOUT));
        String attempt = json.write(AttemptRecorded.fromTransfer(UUID.fromString(MESSAGE_ID), AttemptStage.REQUEST,
                AttemptOutcome.ACCEPTED, null, transferId, UUID.fromString(USER_ID), "key", Money.parse("5.00"), 202,
                UUID.fromString(CORRELATION_ID), Instant.parse("2026-10-19T15:00:00Z")));
        String withoutStatus = json.write(AttemptRecorded.fromTransfer(UUID.fromString(MESSAGE_ID),
                AttemptStage.TRANSFER, AttemptOutcome.COMPLETED, null, null, null, null, null, null,
                UUID.fromString(CORRELATION_ID), Instant.parse("2026-10-19T15:00:00Z")));

        assertThat(reversed).contains("\"amount\":\"5.00\"", "\"reason\":\"CREDIT_TIMEOUT\"", "\"currency\":\"COP\"");
        assertThat(attempt).contains("\"source\":\"TRANSFER\"", "\"httpStatus\":202", "\"idempotencyKey\":\"key\"",
                "\"occurredAt\":\"2026-10-19T15:00:00Z\"");
        assertThat(withoutStatus).contains("\"httpStatus\":null", "\"transferId\":null", "\"amount\":null");
    }

    @Test
    void aValueThatCannotBeWrittenFails() {
        assertThatThrownBy(() -> json.write(new Object())).isInstanceOf(IllegalStateException.class);
    }
}

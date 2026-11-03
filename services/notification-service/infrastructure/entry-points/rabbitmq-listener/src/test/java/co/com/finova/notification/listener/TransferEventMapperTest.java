package co.com.finova.notification.listener;

import co.com.finova.notification.model.notification.TransferEventType;
import co.com.finova.notification.model.notification.TransferOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferEventMapperTest {

    private static final String TRANSFER = "a1b2c3d4-e5f6-4890-abcd-ef1234567890";
    private static final String SENDER = "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b";
    private static final String RECIPIENT = "b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d";

    private final TransferEventMapper mapper = new TransferEventMapper(new ObjectMapper());

    @Test
    void readsATransferCompletedEnvelope() {
        TransferOutcome outcome = mapper.toOutcome(envelope("TransferCompleted",
                "\"completedAt\":\"2026-11-02T15:00:00Z\""));

        assertThat(outcome.type()).isEqualTo(TransferEventType.TRANSFER_COMPLETED);
        assertThat(outcome.transferId()).isEqualTo(UUID.fromString(TRANSFER));
        assertThat(outcome.senderUserId()).isEqualTo(UUID.fromString(SENDER));
        assertThat(outcome.recipientUserId()).isEqualTo(UUID.fromString(RECIPIENT));
        assertThat(outcome.amount()).isEqualTo("150000.00");
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.correlationId()).isEqualTo("5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11");
    }

    @Test
    void readsTheReasonOfRejectedAndReversedTransfers() {
        assertThat(mapper.toOutcome(envelope("TransferRejected", "\"reason\":\"INSUFFICIENT_FUNDS\"")).reason())
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(mapper.toOutcome(envelope("TransferReversed", "\"reason\":\"CREDIT_TIMEOUT\"")).type())
                .isEqualTo(TransferEventType.TRANSFER_REVERSED);
    }

    @Test
    void rejectsABodyThatIsNotJson() {
        assertInvalid("TransferCompleted{transferId=...}".getBytes(StandardCharsets.UTF_8), "not valid JSON");
    }

    @Test
    void rejectsAPayloadSentWithoutTheEnvelope() {
        String bareJson = "{\"transferId\":\"" + TRANSFER + "\",\"amount\":\"1.00\"}";
        assertInvalid(bareJson.getBytes(StandardCharsets.UTF_8), "Missing required field: type");
    }

    @Test
    void rejectsAnUnknownType() {
        assertInvalid(envelope("WalletDebited", "\"completedAt\":\"2026-11-02T15:00:00Z\""), "Unsupported message type");
    }

    @Test
    void rejectsMissingUsers() {
        String body = "{\"type\":\"TransferCompleted\",\"payload\":{\"transferId\":\"" + TRANSFER
                + "\",\"senderUserId\":null,\"recipientUserId\":null,\"amount\":\"150000.00\",\"currency\":\"COP\"}}";
        assertInvalid(body.getBytes(StandardCharsets.UTF_8), "senderUserId");
    }

    @Test
    void rejectsAnAmountWithoutTwoDecimals() {
        String body = "{\"type\":\"TransferCompleted\",\"payload\":{\"transferId\":\"" + TRANSFER
                + "\",\"senderUserId\":\"" + SENDER + "\",\"recipientUserId\":\"" + RECIPIENT
                + "\",\"amount\":\"150000\",\"currency\":\"COP\"}}";
        assertInvalid(body.getBytes(StandardCharsets.UTF_8), "amount");
    }

    @Test
    void rejectsARejectionWithoutReason() {
        assertInvalid(envelope("TransferRejected", "\"note\":\"no reason\""), "Missing required field: reason");
    }

    @Test
    void rejectsABodyThatIsNotAnObject() {
        assertInvalid("[1, 2]".getBytes(StandardCharsets.UTF_8), "JSON envelope");
    }

    @Test
    void rejectsAPayloadThatIsNotAnObject() {
        String body = "{\"type\":\"TransferCompleted\",\"payload\":\"TransferCompleted{...}\"}";
        assertInvalid(body.getBytes(StandardCharsets.UTF_8), "payload must be a JSON object");
    }

    @Test
    void rejectsACurrencyOtherThanCop() {
        String body = "{\"type\":\"TransferCompleted\",\"payload\":{\"transferId\":\"" + TRANSFER
                + "\",\"senderUserId\":\"" + SENDER + "\",\"recipientUserId\":\"" + RECIPIENT
                + "\",\"amount\":\"10.00\",\"currency\":\"USD\"}}";
        assertInvalid(body.getBytes(StandardCharsets.UTF_8), "currency must be COP");
    }

    @Test
    void rejectsAnIdThatIsNotAUuid() {
        String body = "{\"type\":\"TransferCompleted\",\"payload\":{\"transferId\":\"transfer-1"
                + "\",\"senderUserId\":\"" + SENDER + "\",\"recipientUserId\":\"" + RECIPIENT
                + "\",\"amount\":\"10.00\",\"currency\":\"COP\"}}";
        assertInvalid(body.getBytes(StandardCharsets.UTF_8), "transferId is not a UUID");
    }

    private void assertInvalid(byte[] body, String expectedMessage) {
        assertThatThrownBy(() -> mapper.toOutcome(body))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining(expectedMessage);
    }

    static byte[] envelope(String type, String extraPayloadField) {
        String json = """
                {
                  "messageId": "%s",
                  "type": "%s",
                  "correlationId": "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11",
                  "transferId": "%s",
                  "producer": "transfer-service",
                  "occurredAt": "2026-11-02T15:00:00Z",
                  "payload": {
                    "transferId": "%s",
                    "senderUserId": "%s",
                    "recipientUserId": "%s",
                    "amount": "150000.00",
                    "currency": "COP",
                    %s
                  }
                }
                """.formatted(UUID.randomUUID(), type, TRANSFER, TRANSFER, SENDER, RECIPIENT, extraPayloadField);
        return json.getBytes(StandardCharsets.UTF_8);
    }
}

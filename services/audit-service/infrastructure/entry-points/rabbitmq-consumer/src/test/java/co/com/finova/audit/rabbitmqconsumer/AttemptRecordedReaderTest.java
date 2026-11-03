package co.com.finova.audit.rabbitmqconsumer;

import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttemptRecordedReaderTest {

    private static final String EVENT_ID = "8b2d4f6a-1c3e-4a5b-9d7f-2e4a6c8b0d1f";
    private static final String TRANSFER_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String USER_ID = "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b";
    private static final String CORRELATION_ID = "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11";

    private final AttemptRecordedReader reader = new AttemptRecordedReader();

    @Test
    void readsTheEnvelopeTheWalletPublishes() {
        AttemptRecorded attempt = read("""
                {"messageId":"%s","type":"AttemptRecorded","correlationId":"%s","transferId":"%s",
                 "producer":"wallet-service","occurredAt":"2026-10-19T15:00:00.210Z",
                 "payload":{"eventId":"%s","source":"WALLET","stage":"DEBIT","outcome":"APPLIED","reason":null,
                            "transferId":"%s","userId":"%s","idempotencyKey":null,"amount":"150000.00",
                            "httpStatus":null,"correlationId":"%s","occurredAt":"2026-10-19T15:00:00.21Z"}}
                """.formatted(EVENT_ID, CORRELATION_ID, TRANSFER_ID, EVENT_ID, TRANSFER_ID, USER_ID,
                CORRELATION_ID));

        assertEquals(UUID.fromString(EVENT_ID), attempt.eventId());
        assertEquals(AttemptSource.WALLET, attempt.source());
        assertEquals(AttemptStage.DEBIT, attempt.stage());
        assertEquals(AttemptOutcome.APPLIED, attempt.outcome());
        assertNull(attempt.reason());
        assertEquals(UUID.fromString(TRANSFER_ID), attempt.transferId());
        assertEquals(UUID.fromString(USER_ID), attempt.userId());
        assertNull(attempt.idempotencyKey());
        assertEquals("150000.00", attempt.amount());
        assertNull(attempt.statusCode());
        assertEquals(UUID.fromString(CORRELATION_ID), attempt.correlationId());
        assertEquals(Instant.parse("2026-10-19T15:00:00.210Z"), attempt.occurredAt());
    }

    @Test
    void readsAGatewayRejectionPublishedAsABarePayload() {
        AttemptRecorded attempt = read("""
                {"eventId":"%s","source":"GATEWAY","stage":"REQUEST","outcome":"REJECTED",
                 "reason":"FINANCIAL_SESSION_REQUIRED","userId":"%s","idempotencyKey":"7e57d004",
                 "httpStatus":403,"correlationId":"%s","occurredAt":"2026-10-19T10:00:00-05:00"}
                """.formatted(EVENT_ID, USER_ID, CORRELATION_ID));

        assertEquals(AttemptSource.GATEWAY, attempt.source());
        assertEquals("FINANCIAL_SESSION_REQUIRED", attempt.reason());
        assertNull(attempt.transferId());
        assertNull(attempt.amount());
        assertEquals(403, attempt.statusCode());
        assertEquals(Instant.parse("2026-10-19T15:00:00Z"), attempt.occurredAt());
    }

    @Test
    void takesEventIdTransferIdAndCorrelationIdFromTheEnvelopeWhenThePayloadLacksThem() {
        AttemptRecorded attempt = read("""
                {"messageId":"%s","type":"AttemptRecorded","correlationId":"%s","transferId":"%s",
                 "exchange":"audit.events","routingKey":"audit.attempt",
                 "payload":{"source":"TRANSFER","stage":"TRANSFER","outcome":"COMPLETED",
                            "occurredAt":1760886000.123456789}}
                """.formatted(EVENT_ID, CORRELATION_ID, TRANSFER_ID));

        assertEquals(UUID.fromString(EVENT_ID), attempt.eventId());
        assertEquals(UUID.fromString(TRANSFER_ID), attempt.transferId());
        assertEquals(UUID.fromString(CORRELATION_ID), attempt.correlationId());
        assertEquals(Instant.parse("2025-10-19T15:00:00.123456Z"), attempt.occurredAt());
    }

    @Test
    void acceptsEpochMillisAndANumericAmount() {
        AttemptRecorded attempt = read("""
                {"eventId":"%s","source":"TRANSFER","stage":"REQUEST","outcome":"ACCEPTED","amount":150000,
                 "correlationId":"%s","occurredAt":1760886000123}
                """.formatted(EVENT_ID, CORRELATION_ID));

        assertEquals("150000.00", attempt.amount());
        assertEquals(Instant.parse("2025-10-19T15:00:00.123Z"), attempt.occurredAt());
    }

    @Test
    void rejectsWhatBreaksTheContract() {
        String valid = """
                {"eventId":"%s","source":"GATEWAY","stage":"REQUEST","outcome":"REJECTED",
                 "correlationId":"%s","occurredAt":"2026-10-19T15:00:00Z"%s}""";
        assertMalformed("not json");
        assertMalformed("[1, 2]");
        assertMalformed("""
                {"type":"DebitWallet","payload":{"transferId":"%s"}}""".formatted(TRANSFER_ID));
        assertMalformed(valid.formatted(EVENT_ID, CORRELATION_ID, ",\"amount\":\"150000.5\""));
        assertMalformed(valid.formatted(EVENT_ID, CORRELATION_ID, ",\"amount\":1.234"));
        assertMalformed(valid.formatted(EVENT_ID, CORRELATION_ID, ",\"httpStatus\":\"forbidden\""));
        assertMalformed(valid.formatted(EVENT_ID, CORRELATION_ID, ",\"userId\":\"user-1\""));
        assertMalformed(valid.formatted(EVENT_ID, "corr-1", ""));
        assertMalformed(valid.replace("GATEWAY", "BANK").formatted(EVENT_ID, CORRELATION_ID, ""));
        assertMalformed(valid.replace("\"outcome\":\"REJECTED\",", "").formatted(EVENT_ID, CORRELATION_ID, ""));
        assertMalformed(valid.replace("2026-10-19T15:00:00Z", "yesterday").formatted(EVENT_ID, CORRELATION_ID, ""));
    }

    private AttemptRecorded read(String json) {
        return reader.read(json.getBytes(StandardCharsets.UTF_8));
    }

    private void assertMalformed(String json) {
        assertThrows(MalformedAttemptException.class, () -> read(json), json);
    }
}

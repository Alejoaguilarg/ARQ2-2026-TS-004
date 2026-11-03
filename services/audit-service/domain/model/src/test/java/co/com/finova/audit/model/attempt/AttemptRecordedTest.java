package co.com.finova.audit.model.attempt;

import co.com.finova.audit.model.common.ContractViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttemptRecordedTest {

    private static final UUID EVENT_ID = UUID.fromString("3f9a6c1e-7b2d-4c5e-8f9a-0b1c2d3e4f5a");
    private static final UUID CORRELATION_ID = UUID.fromString("5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-19T15:00:00Z");

    @Test
    void acceptsAGatewayRejectionWithoutUserTransferOrAmount() {
        AttemptRecorded attempt = new AttemptRecorded(EVENT_ID, AttemptSource.GATEWAY, AttemptStage.REQUEST,
                AttemptOutcome.REJECTED, "UNAUTHORIZED", null, null, null, null, 401, CORRELATION_ID, OCCURRED_AT);

        assertNull(attempt.userId());
        assertNull(attempt.transferId());
        assertNull(attempt.amount());
        assertEquals(401, attempt.statusCode());
    }

    @Test
    void requiresTheContractFields() {
        assertThrows(ContractViolationException.class, () -> attempt(null, AttemptSource.WALLET, CORRELATION_ID,
                OCCURRED_AT, "1.00", null));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, null, CORRELATION_ID,
                OCCURRED_AT, "1.00", null));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.WALLET, null,
                OCCURRED_AT, "1.00", null));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.WALLET,
                CORRELATION_ID, null, "1.00", null));
        assertThrows(ContractViolationException.class, () -> new AttemptRecorded(EVENT_ID, AttemptSource.WALLET,
                null, AttemptOutcome.APPLIED, null, null, null, null, null, null, CORRELATION_ID, OCCURRED_AT));
        assertThrows(ContractViolationException.class, () -> new AttemptRecorded(EVENT_ID, AttemptSource.WALLET,
                AttemptStage.DEBIT, null, null, null, null, null, null, null, CORRELATION_ID, OCCURRED_AT));
    }

    @Test
    void amountMustHaveExactlyTwoDecimals() {
        assertDoesNotThrow(() -> attempt(EVENT_ID, AttemptSource.WALLET, CORRELATION_ID, OCCURRED_AT,
                "150000.00", null));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.WALLET,
                CORRELATION_ID, OCCURRED_AT, "150000", null));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.WALLET,
                CORRELATION_ID, OCCURRED_AT, "150000.5", null));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.WALLET,
                CORRELATION_ID, OCCURRED_AT, "-10.00", null));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.WALLET,
                CORRELATION_ID, OCCURRED_AT, "1e3.00", null));
    }

    @Test
    void amountIsNormalizedAsAudit_dbStoresIt() {
        assertEquals("150.00", attempt(EVENT_ID, AttemptSource.WALLET, CORRELATION_ID, OCCURRED_AT,
                "000150.00", null).amount());
        assertEquals("0.50", attempt(EVENT_ID, AttemptSource.WALLET, CORRELATION_ID, OCCURRED_AT,
                "00.50", null).amount());
    }

    @Test
    void statusCodeMustBeAValidStatus() {
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.GATEWAY,
                CORRELATION_ID, OCCURRED_AT, null, 99));
        assertThrows(ContractViolationException.class, () -> attempt(EVENT_ID, AttemptSource.GATEWAY,
                CORRELATION_ID, OCCURRED_AT, null, 600));
        assertEquals(504, attempt(EVENT_ID, AttemptSource.GATEWAY, CORRELATION_ID, OCCURRED_AT, null, 504)
                .statusCode());
    }

    @Test
    void occurredAtKeepsMicrosecondPrecisionLikeTimestamptz() {
        AttemptRecorded attempt = attempt(EVENT_ID, AttemptSource.WALLET, CORRELATION_ID,
                Instant.parse("2026-10-19T15:00:00.123456789Z"), null, null);

        assertEquals(Instant.parse("2026-10-19T15:00:00.123456Z"), attempt.occurredAt());
    }

    private static AttemptRecorded attempt(UUID eventId, AttemptSource source, UUID correlationId,
                                           Instant occurredAt, String amount, Integer statusCode) {
        return new AttemptRecorded(eventId, source, AttemptStage.REQUEST, AttemptOutcome.ACCEPTED, null, null,
                null, null, amount, statusCode, correlationId, occurredAt);
    }
}

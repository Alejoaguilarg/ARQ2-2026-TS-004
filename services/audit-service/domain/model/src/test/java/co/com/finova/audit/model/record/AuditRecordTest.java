package co.com.finova.audit.model.record;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.common.ContractViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRecordTest {

    private static final AttemptRecorded ATTEMPT = HashChainTest.SPEC_EXAMPLE;

    @Test
    void theFirstRecordLinksToTheGenesis() {
        AuditRecord first = AuditRecord.append(ChainLink.GENESIS, ATTEMPT, HashChainTest.RECORDED_AT);

        assertEquals(1, first.seq());
        assertEquals(HashChain.GENESIS_HASH, first.prevHash());
        assertTrue(first.isIntact());
    }

    @Test
    void aRecordLinksToThePreviousOne() {
        AuditRecord first = AuditRecord.append(ChainLink.GENESIS, ATTEMPT, HashChainTest.RECORDED_AT);
        AuditRecord second = AuditRecord.append(first.link(), ATTEMPT, HashChainTest.RECORDED_AT.plusSeconds(1));

        assertEquals(2, second.seq());
        assertEquals(first.hash(), second.prevHash());
        assertEquals(HashChain.hash(first.hash(), 2, ATTEMPT, second.recordedAt()), second.hash());
    }

    @Test
    void recordedAtKeepsMicrosecondPrecision() {
        AuditRecord record = AuditRecord.append(ChainLink.GENESIS, ATTEMPT,
                Instant.parse("2026-10-19T15:00:00.040000999Z"));

        assertEquals(Instant.parse("2026-10-19T15:00:00.040Z"), record.recordedAt());
        assertTrue(record.isIntact());
    }

    @Test
    void aRecordWhoseContentWasChangedIsNotIntact() {
        AuditRecord original = AuditRecord.append(ChainLink.GENESIS, ATTEMPT, HashChainTest.RECORDED_AT);
        AttemptRecorded altered = new AttemptRecorded(ATTEMPT.eventId(), ATTEMPT.source(), ATTEMPT.stage(),
                ATTEMPT.outcome(), ATTEMPT.reason(), ATTEMPT.transferId(), ATTEMPT.userId(),
                ATTEMPT.idempotencyKey(), "1.00", ATTEMPT.statusCode(), ATTEMPT.correlationId(),
                ATTEMPT.occurredAt());

        AuditRecord tampered = new AuditRecord(original.seq(), altered, original.recordedAt(),
                original.prevHash(), original.hash());

        assertFalse(tampered.isIntact());
    }

    @Test
    void hashesMustBeSixtyFourLowercaseHexCharacters() {
        assertThrows(ContractViolationException.class, () -> new AuditRecord(1, ATTEMPT,
                HashChainTest.RECORDED_AT, "ABC", HashChain.GENESIS_HASH));
        assertThrows(ContractViolationException.class, () -> new AuditRecord(1, ATTEMPT,
                HashChainTest.RECORDED_AT, HashChain.GENESIS_HASH, "F".repeat(64)));
        assertThrows(ContractViolationException.class, () -> new AuditRecord(0, ATTEMPT,
                HashChainTest.RECORDED_AT, HashChain.GENESIS_HASH, HashChain.GENESIS_HASH));
    }
}

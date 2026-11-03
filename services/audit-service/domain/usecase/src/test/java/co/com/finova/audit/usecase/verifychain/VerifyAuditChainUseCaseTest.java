package co.com.finova.audit.usecase.verifychain;

import co.com.finova.audit.model.query.InvalidAuditQueryException;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.HashChain;
import co.com.finova.audit.model.verification.ChainVerification;
import co.com.finova.audit.usecase.recordattempt.RecordAttemptUseCase;
import co.com.finova.audit.usecase.support.Attempts;
import co.com.finova.audit.usecase.support.DirectTransactionRunner;
import co.com.finova.audit.usecase.support.InMemoryAuditRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyAuditChainUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-19T16:00:00Z");

    private InMemoryAuditRecordRepository records;
    private RecordAttemptUseCase recordAttempt;
    private VerifyAuditChainUseCase verify;

    @BeforeEach
    void setUp() {
        records = new InMemoryAuditRecordRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        recordAttempt = new RecordAttemptUseCase(records, new DirectTransactionRunner(), clock);
        verify = new VerifyAuditChainUseCase(records, clock);
    }

    @Test
    void anEmptyTrailIsValid() {
        ChainVerification result = verify.handle(null, null);

        assertTrue(result.valid());
        assertEquals(0, result.checkedRecords());
        assertNull(result.firstInvalidSeq());
        assertEquals(NOW, result.verifiedAt());
    }

    @Test
    void anIntactChainIsValidAcrossSeveralBatches() {
        store(VerifyAuditChainUseCase.BATCH_SIZE * 2 + 5);

        ChainVerification result = verify.handle(null, null);

        assertTrue(result.valid());
        assertEquals(VerifyAuditChainUseCase.BATCH_SIZE * 2 + 5, result.checkedRecords());
        assertNull(result.firstInvalidSeq());
    }

    @Test
    void aRecordAlteredWithoutItsHashIsTheFirstInvalidSeqAndTheWholeRangeIsChecked() {
        store(10);
        AuditRecord original = records.get(4);
        records.tamper(new AuditRecord(4, Attempts.withAmount(original.attempt(), "999999.00"),
                original.recordedAt(), original.prevHash(), original.hash()));

        ChainVerification result = verify.handle(null, null);

        assertFalse(result.valid());
        assertEquals(4L, result.firstInvalidSeq());
        assertEquals(10, result.checkedRecords());
    }

    @Test
    void aRecordAlteredWithARecomputedHashBreaksTheLinkOfTheNextRecord() {
        store(10);
        AuditRecord original = records.get(4);
        var altered = Attempts.withAmount(original.attempt(), "999999.00");
        records.tamper(new AuditRecord(4, altered, original.recordedAt(), original.prevHash(),
                HashChain.hash(original.prevHash(), 4, altered, original.recordedAt())));

        ChainVerification result = verify.handle(null, null);

        assertFalse(result.valid());
        assertEquals(5L, result.firstInvalidSeq());
    }

    @Test
    void aDeletedRecordIsReportedAsTheFirstInvalidSeq() {
        store(10);
        records.remove(6);

        ChainVerification result = verify.handle(null, null);

        assertFalse(result.valid());
        assertEquals(6L, result.firstInvalidSeq());
        assertEquals(9, result.checkedRecords());
    }

    @Test
    void anAlteredFirstRecordIsDetected() {
        store(3);
        AuditRecord first = records.get(1);
        records.tamper(new AuditRecord(1, first.attempt(), first.recordedAt(), "1".repeat(64), first.hash()));

        assertEquals(1L, verify.handle(null, null).firstInvalidSeq());
    }

    @Test
    void aPartialRangeIsAnchoredOnThePreviousRecord() {
        store(10);

        ChainVerification result = verify.handle(4L, 7L);

        assertTrue(result.valid());
        assertEquals(4, result.checkedRecords());
    }

    @Test
    void aPartialRangeWhosePreviousRecordIsMissingIsInvalid() {
        store(10);
        records.remove(3);

        ChainVerification result = verify.handle(4L, 7L);

        assertFalse(result.valid());
        assertEquals(3L, result.firstInvalidSeq());
    }

    @Test
    void toSeqBeyondTheLastRecordStopsAtTheLastRecord() {
        store(5);

        ChainVerification result = verify.handle(2L, 1_000L);

        assertTrue(result.valid());
        assertEquals(4, result.checkedRecords());
    }

    @Test
    void aRangeAfterTheLastRecordChecksNothing() {
        store(5);

        ChainVerification result = verify.handle(9L, null);

        assertTrue(result.valid());
        assertEquals(0, result.checkedRecords());
    }

    @Test
    void rejectsInvalidRanges() {
        assertEquals("fromSeq", assertThrows(InvalidAuditQueryException.class,
                () -> verify.handle(0L, null)).field());
        assertEquals("toSeq", assertThrows(InvalidAuditQueryException.class,
                () -> verify.handle(null, 0L)).field());
        assertEquals("toSeq", assertThrows(InvalidAuditQueryException.class,
                () -> verify.handle(5L, 4L)).field());
    }

    private void store(int count) {
        for (int i = 0; i < count; i++) {
            recordAttempt.handle(Attempts.any());
        }
    }
}

package co.com.finova.audit.usecase.recordattempt;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.HashChain;
import co.com.finova.audit.model.record.RecordOutcome;
import co.com.finova.audit.usecase.support.Attempts;
import co.com.finova.audit.usecase.support.DirectTransactionRunner;
import co.com.finova.audit.usecase.support.InMemoryAuditRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordAttemptUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-19T15:00:00.040Z");

    private InMemoryAuditRecordRepository records;
    private DirectTransactionRunner transactions;
    private RecordAttemptUseCase useCase;

    @BeforeEach
    void setUp() {
        records = new InMemoryAuditRecordRepository();
        transactions = new DirectTransactionRunner();
        useCase = new RecordAttemptUseCase(records, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theFirstAttemptIsStoredWithSeqOneLinkedToTheGenesis() {
        RecordOutcome outcome = useCase.handle(Attempts.any());

        assertFalse(outcome.duplicate());
        assertEquals(1L, outcome.seq());
        AuditRecord stored = records.get(1);
        assertEquals(HashChain.GENESIS_HASH, stored.prevHash());
        assertEquals(NOW, stored.recordedAt());
        assertTrue(stored.isIntact());
        assertEquals(1, transactions.transactions());
    }

    @Test
    void eachAttemptGetsTheNextSeqAndLinksToThePreviousHash() {
        useCase.handle(Attempts.any());
        useCase.handle(Attempts.any());
        useCase.handle(Attempts.any());

        assertEquals(3, records.all().size());
        assertEquals(records.get(1).hash(), records.get(2).prevHash());
        assertEquals(records.get(2).hash(), records.get(3).prevHash());
    }

    @Test
    void theSameEventIdDeliveredTwiceIsIgnoredAndTheChainIsUnchanged() {
        AttemptRecorded attempt = Attempts.any();
        useCase.handle(attempt);
        AuditRecord before = records.get(1);

        RecordOutcome second = useCase.handle(attempt);

        assertTrue(second.duplicate());
        assertNull(second.seq());
        assertEquals(1, records.all().size());
        assertEquals(before, records.get(1));
    }

    @Test
    void takesTheAppendLockBeforeLookingForDuplicates() {
        useCase.handle(Attempts.any());

        assertEquals(List.of("lockChain", "contains", "append"), records.calls());
    }
}

package co.com.finova.audit.usecase.queryrecords;

import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import co.com.finova.audit.model.query.AuditPage;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.usecase.recordattempt.RecordAttemptUseCase;
import co.com.finova.audit.usecase.support.Attempts;
import co.com.finova.audit.usecase.support.DirectTransactionRunner;
import co.com.finova.audit.usecase.support.InMemoryAuditRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryAuditRecordsUseCaseTest {

    private static final UUID TRANSFER_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID USER_ID = UUID.fromString("f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b");

    private RecordAttemptUseCase recordAttempt;
    private QueryAuditRecordsUseCase query;

    @BeforeEach
    void setUp() {
        InMemoryAuditRecordRepository records = new InMemoryAuditRecordRepository();
        recordAttempt = new RecordAttemptUseCase(records, new DirectTransactionRunner(),
                Clock.fixed(Attempts.T0, ZoneOffset.UTC));
        query = new QueryAuditRecordsUseCase(records);
    }

    @Test
    void returnsEveryRecordOfATransferIncludingGatewayDuplicatesInSeqOrder() {
        recordAttempt.handle(gatewayDuplicate());
        recordAttempt.handle(Attempts.walletDebit(UUID.randomUUID(), TRANSFER_ID, USER_ID, Attempts.T0));
        recordAttempt.handle(Attempts.any());
        recordAttempt.handle(Attempts.walletDebit(UUID.randomUUID(), TRANSFER_ID, USER_ID, Attempts.T0));

        AuditPage page = query.handle(AuditQuery.of(TRANSFER_ID, null, null, null, null, null));

        assertEquals(3, page.total());
        assertEquals(List.of(1L, 2L, 4L), page.items().stream().map(AuditRecord::seq).toList());
        assertEquals(AttemptSource.GATEWAY, page.items().get(0).attempt().source());
    }

    @Test
    void pagesKeepTheTotalOfTheWholeResult() {
        for (int i = 0; i < 5; i++) {
            recordAttempt.handle(Attempts.walletDebit(UUID.randomUUID(), TRANSFER_ID, USER_ID, Attempts.T0));
        }

        AuditPage page = query.handle(AuditQuery.of(TRANSFER_ID, null, null, null, 1, 2));

        assertEquals(5, page.total());
        assertEquals(List.of(3L, 4L), page.items().stream().map(AuditRecord::seq).toList());
    }

    private static AttemptRecorded gatewayDuplicate() {
        return new AttemptRecorded(UUID.randomUUID(), AttemptSource.GATEWAY, AttemptStage.REQUEST,
                AttemptOutcome.DUPLICATE, "IN_PROGRESS_CONFLICT", TRANSFER_ID, USER_ID, "key-1", "150000.00",
                409, UUID.randomUUID(), Attempts.T0);
    }
}

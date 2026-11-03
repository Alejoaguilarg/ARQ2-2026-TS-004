package co.com.finova.audit.usecase.recordattempt;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.common.Contract;
import co.com.finova.audit.model.common.gateways.TransactionRunner;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;
import co.com.finova.audit.model.record.RecordOutcome;
import co.com.finova.audit.model.record.gateways.AuditRecordRepository;

import java.time.Clock;

/**
 * Stores one AttemptRecorded at the end of the hash chain (spec §4.8, F5.2, F5.3). In one transaction:
 * take the append lock, ignore the event if its eventId is already stored, link the new record to the
 * last one (seq + 1, prevHash = its hash) and insert it with its audit_event_ids row.
 */
public class RecordAttemptUseCase {

    private static final System.Logger LOG = System.getLogger(RecordAttemptUseCase.class.getName());

    private final AuditRecordRepository records;
    private final TransactionRunner transactions;
    private final Clock clock;

    public RecordAttemptUseCase(AuditRecordRepository records, TransactionRunner transactions, Clock clock) {
        this.records = records;
        this.transactions = transactions;
        this.clock = clock;
    }

    public RecordOutcome handle(AttemptRecorded attempt) {
        Contract.requireNonNull(attempt, "attempt");
        RecordOutcome outcome = transactions.inTransaction(() -> append(attempt));
        if (outcome.duplicate()) {
            LOG.log(System.Logger.Level.INFO, () -> "duplicate AttemptRecorded ignored" + context(attempt));
        } else {
            LOG.log(System.Logger.Level.DEBUG, () -> "AttemptRecorded stored with seq=" + outcome.seq()
                    + context(attempt));
        }
        return outcome;
    }

    private RecordOutcome append(AttemptRecorded attempt) {
        records.lockChain();
        if (records.contains(attempt.eventId())) {
            return RecordOutcome.ignoredDuplicate(attempt.eventId());
        }
        ChainLink last = records.findLastLink().orElse(ChainLink.GENESIS);
        AuditRecord record = AuditRecord.append(last, attempt, clock.instant());
        records.append(record);
        return RecordOutcome.recorded(attempt.eventId(), record.seq());
    }

    private static String context(AttemptRecorded attempt) {
        return " eventId=" + attempt.eventId() + " transferId=" + attempt.transferId()
                + " correlationId=" + attempt.correlationId();
    }
}

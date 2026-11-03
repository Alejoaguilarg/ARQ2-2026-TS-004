package co.com.finova.audit.model.attempt;

/** Spec §4.4 AttemptRecorded.stage. */
public enum AttemptStage {
    REQUEST,
    DEBIT,
    CREDIT,
    REVERSAL,
    TRANSFER
}

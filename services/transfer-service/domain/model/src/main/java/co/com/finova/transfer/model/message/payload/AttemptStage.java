package co.com.finova.transfer.model.message.payload;

/** Spec §4.4 AttemptRecorded.stage. */
public enum AttemptStage {
    REQUEST,
    DEBIT,
    CREDIT,
    REVERSAL,
    TRANSFER
}

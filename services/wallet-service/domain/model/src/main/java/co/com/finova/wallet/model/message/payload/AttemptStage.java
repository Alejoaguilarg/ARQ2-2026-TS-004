package co.com.finova.wallet.model.message.payload;

/** Spec §4.4 AttemptRecorded.stage. */
public enum AttemptStage {
    REQUEST,
    DEBIT,
    CREDIT,
    REVERSAL,
    TRANSFER
}

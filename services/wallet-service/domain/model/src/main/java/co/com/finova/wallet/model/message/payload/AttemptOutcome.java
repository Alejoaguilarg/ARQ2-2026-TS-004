package co.com.finova.wallet.model.message.payload;

/** Spec §4.4 AttemptRecorded.outcome. */
public enum AttemptOutcome {
    ACCEPTED,
    REJECTED,
    DUPLICATE,
    FAILED,
    COMPENSATED,
    COMPLETED,
    APPLIED
}

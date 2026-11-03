package co.com.finova.audit.model.attempt;

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

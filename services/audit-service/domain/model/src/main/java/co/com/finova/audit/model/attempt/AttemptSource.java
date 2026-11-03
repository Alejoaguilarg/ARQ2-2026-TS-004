package co.com.finova.audit.model.attempt;

/** Spec §4.4 AttemptRecorded.source: the service that saw the attempt. */
public enum AttemptSource {
    GATEWAY,
    TRANSFER,
    WALLET
}

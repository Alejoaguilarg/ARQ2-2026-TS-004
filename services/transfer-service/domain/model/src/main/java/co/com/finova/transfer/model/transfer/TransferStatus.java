package co.com.finova.transfer.model.transfer;

/** Spec §2 TransferStatus; COMPLETED, REJECTED and REVERSED are terminal. */
public enum TransferStatus {
    PENDING,
    DEBITED,
    COMPLETED,
    REJECTED,
    COMPENSATING,
    REVERSED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == REVERSED;
    }
}

package co.com.finova.transfer.model.transfer;

import co.com.finova.transfer.model.message.payload.DebitRejectionReason;
import co.com.finova.transfer.model.message.payload.ReverseReason;

/** Spec §2 Transfer.failureReason: the asynchronous outcome of a failed or compensated transfer (§3.2). */
public enum FailureReason {
    INSUFFICIENT_FUNDS,
    SENDER_WALLET_NOT_FOUND,
    RECIPIENT_WALLET_NOT_FOUND,
    CREDIT_TIMEOUT;

    public static FailureReason of(DebitRejectionReason reason) {
        return valueOf(reason.name());
    }

    public static FailureReason of(ReverseReason reason) {
        return valueOf(reason.name());
    }

    /** The reason sent in ReverseDebit and TransferReversed; only the two compensation reasons have one. */
    public ReverseReason toReverseReason() {
        return ReverseReason.valueOf(name());
    }

    public DebitRejectionReason toDebitRejectionReason() {
        return DebitRejectionReason.valueOf(name());
    }
}

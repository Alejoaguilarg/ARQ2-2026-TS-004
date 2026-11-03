package co.com.finova.wallet.model.message.payload;

/** Spec §4.4 DebitRejected.reason. */
public enum DebitRejectionReason {
    INSUFFICIENT_FUNDS,
    SENDER_WALLET_NOT_FOUND
}

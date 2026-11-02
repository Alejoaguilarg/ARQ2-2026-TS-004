package co.com.finova.wallet.model.message.payload;

/** Spec §4.4 ReverseDebit.reason. */
public enum ReverseReason {
    RECIPIENT_WALLET_NOT_FOUND,
    CREDIT_TIMEOUT
}

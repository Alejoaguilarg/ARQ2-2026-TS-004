package co.com.finova.transfer.model.message.payload;

/** Spec §4.4 CreditFailed.reason; TRANSFER_REVERSED means a late CreditWallet arrived after the reversal. */
public enum CreditFailureReason {
    RECIPIENT_WALLET_NOT_FOUND,
    TRANSFER_REVERSED
}

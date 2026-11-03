package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.message.MessageType;

/** The {@code payload} of an envelope (spec §4.4): one record per message Transfer publishes or consumes. */
public sealed interface MessagePayload
        permits DebitWallet, CreditWallet, ReverseDebit,
        WalletDebited, DebitRejected, WalletCredited, CreditFailed, DebitReversed,
        TransferCompleted, TransferRejected, TransferReversed, AttemptRecorded {

    MessageType type();
}

package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.message.MessageType;

/** The {@code payload} of an envelope (spec §4.4): one record per message the Wallet consumes or publishes. */
public sealed interface MessagePayload
        permits DebitWallet, CreditWallet, ReverseDebit,
        WalletDebited, DebitRejected, WalletCredited, CreditFailed, DebitReversed,
        BalanceChanged, AttemptRecorded {

    MessageType type();
}

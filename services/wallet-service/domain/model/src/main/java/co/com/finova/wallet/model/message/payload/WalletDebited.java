package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;

import java.util.UUID;

/** Result Wallet → Transfer (spec §4.4): the debit was applied. */
public record WalletDebited(UUID transferId, UUID walletId, UUID userId, Money amount, long version)
        implements MessagePayload {

    public WalletDebited {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(walletId, "walletId");
        Contract.requireNonNull(userId, "userId");
        PayloadRules.requirePositiveAmount(amount);
        PayloadRules.requireVersion(version);
    }

    public static WalletDebited from(UUID transferId, Money amount, Wallet walletAfter) {
        Contract.requireNonNull(walletAfter, "walletAfter");
        return new WalletDebited(transferId, walletAfter.walletId(), walletAfter.userId(), amount,
                walletAfter.version());
    }

    @Override
    public MessageType type() {
        return MessageType.WALLET_DEBITED;
    }
}

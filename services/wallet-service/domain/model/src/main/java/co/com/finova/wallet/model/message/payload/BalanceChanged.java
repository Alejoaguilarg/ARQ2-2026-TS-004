package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;

import java.util.UUID;

/**
 * Balance read model event (spec §4.4, ADR 004). version is monotonic per wallet; the envelope
 * occurredAt is stored as asOf. Available may be zero, so it is not required to be positive.
 */
public record BalanceChanged(UUID walletId, UUID userId, Money available, long version) implements MessagePayload {

    public BalanceChanged {
        Contract.requireNonNull(walletId, "walletId");
        Contract.requireNonNull(userId, "userId");
        Contract.requireNonNull(available, "available");
        PayloadRules.requireVersion(version);
    }

    public static BalanceChanged from(Wallet wallet) {
        Contract.requireNonNull(wallet, "wallet");
        return new BalanceChanged(wallet.walletId(), wallet.userId(), wallet.balance(), wallet.version());
    }

    @Override
    public MessageType type() {
        return MessageType.BALANCE_CHANGED;
    }
}

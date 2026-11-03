package co.com.finova.wallet.model.balance;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the balance read model (spec §2.1 balance:{userId}, ADR 004) and the body of
 * GET /wallets/me/balance (§3.7). Eventually consistent: it is never used to authorize a debit.
 * asOf is the instant the balance was produced, so the same version has the same asOf whether it
 * comes from BalanceChanged (occurredAt) or from the wallet (updatedAt).
 */
public record BalanceSnapshot(UUID walletId, UUID userId, Money available, Currency currency, long version,
                              Instant asOf) {

    public BalanceSnapshot {
        Contract.requireNonNull(walletId, "walletId");
        Contract.requireNonNull(userId, "userId");
        Contract.requireNonNull(available, "available");
        Contract.requireNonNull(currency, "currency");
        Contract.require(version >= 0, "version must be >= 0: " + version);
        Contract.requireNonNull(asOf, "asOf");
    }

    /** Cache-miss path: the wallet read from wallet_db. */
    public static BalanceSnapshot fromWallet(Wallet wallet) {
        Contract.requireNonNull(wallet, "wallet");
        return new BalanceSnapshot(wallet.walletId(), wallet.userId(), wallet.balance(), wallet.currency(),
                wallet.version(), wallet.updatedAt());
    }

    /**
     * Projection path. BalanceChanged carries no currency (§4.4); COP is the only currency (§1.5).
     * The envelope occurredAt is stored as asOf (§4.4).
     */
    public static BalanceSnapshot fromEvent(MessageEnvelope<BalanceChanged> event) {
        Contract.requireNonNull(event, "event");
        BalanceChanged changed = event.payload();
        return new BalanceSnapshot(changed.walletId(), changed.userId(), changed.available(), Currency.COP,
                changed.version(), event.occurredAt());
    }
}

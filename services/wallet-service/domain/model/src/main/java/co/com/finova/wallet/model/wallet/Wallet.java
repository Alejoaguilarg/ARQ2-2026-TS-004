package co.com.finova.wallet.model.wallet;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * One wallet per user (spec §2 Wallet). The balance never goes below zero ({@link Money} rejects it,
 * and so does CHECK (balance >= 0)); version grows by one on every debit, credit and reversal.
 */
public record Wallet(UUID walletId, UUID userId, Money balance, Currency currency, long version, Instant updatedAt) {

    public Wallet {
        Contract.requireNonNull(walletId, "walletId");
        Contract.requireNonNull(userId, "userId");
        Contract.requireNonNull(balance, "balance");
        Contract.requireNonNull(currency, "currency");
        Contract.require(version >= 0, "version must be >= 0: " + version);
        Contract.requireNonNull(updatedAt, "updatedAt");
    }
}

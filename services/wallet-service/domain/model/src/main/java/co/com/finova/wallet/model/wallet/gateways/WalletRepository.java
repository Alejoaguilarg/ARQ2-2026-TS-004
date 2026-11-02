package co.com.finova.wallet.model.wallet.gateways;

import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    Optional<Wallet> findByUserId(UUID userId);

    /**
     * Conditional atomic debit (spec §4.7): a single
     * {@code UPDATE wallets SET balance = balance - :amount, version = version + 1
     * WHERE user_id = :userId AND balance >= :amount RETURNING ...}. Never read-then-write.
     *
     * @return the updated wallet, or empty when no row was updated (missing wallet or insufficient funds)
     */
    Optional<Wallet> debitIfSufficient(UUID userId, Money amount);

    /** Adds the amount and increments the version (credit and reversal). Returns the updated wallet. */
    Wallet increaseBalance(UUID walletId, Money amount);
}

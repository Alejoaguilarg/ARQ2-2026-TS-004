package co.com.finova.wallet.model.wallet.gateways;

import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * updatedAt is the instant of the operation, the same one used as occurredAt of its BalanceChanged,
 * so the balance read model has the same asOf whether it comes from the event or from the wallet.
 */
public interface WalletRepository {

    Optional<Wallet> findByUserId(UUID userId);

    /**
     * Conditional atomic debit (spec §4.7): a single
     * {@code UPDATE wallets SET balance = balance - :amount, version = version + 1
     * WHERE user_id = :userId AND balance >= :amount RETURNING ...}. Never read-then-write.
     *
     * @return the updated wallet, or empty when no row was updated (missing wallet or insufficient funds)
     */
    Optional<Wallet> debitIfSufficient(UUID userId, Money amount, Instant updatedAt);

    /** Adds the amount and increments the version (credit and reversal). Returns the updated wallet. */
    Wallet increaseBalance(UUID walletId, Money amount, Instant updatedAt);

    /**
     * Keyset page for the balance cache rebuild (spec §4.5, decision S6): up to limit wallets whose walletId
     * comes after afterWalletId in the database order, or from the first one when afterWalletId is null.
     */
    List<Wallet> findPageAfter(UUID afterWalletId, int limit);
}

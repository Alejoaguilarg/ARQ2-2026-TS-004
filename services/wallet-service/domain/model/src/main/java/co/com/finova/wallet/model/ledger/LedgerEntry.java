package co.com.finova.wallet.model.ledger;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a financial effect (spec §2 LedgerEntry). transferId is null only for OPENING;
 * the amount is always positive and balanceAfter is the wallet balance right after the effect.
 */
public record LedgerEntry(UUID entryId, UUID walletId, UUID transferId, LedgerEntryType type,
                          Money amount, Money balanceAfter, Instant createdAt) {

    public LedgerEntry {
        Contract.requireNonNull(entryId, "entryId");
        Contract.requireNonNull(walletId, "walletId");
        Contract.requireNonNull(type, "type");
        Contract.require((type == LedgerEntryType.OPENING) == (transferId == null),
                "transferId must be null only for OPENING entries (type " + type + ")");
        Contract.requireNonNull(amount, "amount");
        Contract.require(amount.isPositive(), "amount must be > 0");
        Contract.requireNonNull(balanceAfter, "balanceAfter");
        Contract.requireNonNull(createdAt, "createdAt");
    }

    /** Initial balance of a provisioned wallet (spec §1.6). */
    public static LedgerEntry opening(UUID entryId, UUID walletId, Money amount, Instant createdAt) {
        return new LedgerEntry(entryId, walletId, null, LedgerEntryType.OPENING, amount, amount, createdAt);
    }

    public static LedgerEntry debit(UUID entryId, UUID transferId, Money amount, Wallet walletAfter,
                                    Instant createdAt) {
        return forTransfer(entryId, transferId, LedgerEntryType.DEBIT, amount, walletAfter, createdAt);
    }

    public static LedgerEntry credit(UUID entryId, UUID transferId, Money amount, Wallet walletAfter,
                                     Instant createdAt) {
        return forTransfer(entryId, transferId, LedgerEntryType.CREDIT, amount, walletAfter, createdAt);
    }

    public static LedgerEntry reversal(UUID entryId, UUID transferId, Money amount, Wallet walletAfter,
                                       Instant createdAt) {
        return forTransfer(entryId, transferId, LedgerEntryType.REVERSAL, amount, walletAfter, createdAt);
    }

    private static LedgerEntry forTransfer(UUID entryId, UUID transferId, LedgerEntryType type, Money amount,
                                           Wallet walletAfter, Instant createdAt) {
        Contract.requireNonNull(walletAfter, "walletAfter");
        return new LedgerEntry(entryId, walletAfter.walletId(), transferId, type, amount,
                walletAfter.balance(), createdAt);
    }
}

package co.com.finova.wallet.model.ledger;

/** Spec §2 LedgerEntry.type. */
public enum LedgerEntryType {
    OPENING,
    DEBIT,
    CREDIT,
    REVERSAL
}

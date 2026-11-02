package co.com.finova.wallet.model.ledger.gateways;

import co.com.finova.wallet.model.ledger.LedgerEntry;

public interface LedgerRepository {

    /** Inserts the entry; the ledger is never updated or deleted (spec §2 LedgerEntry). */
    void append(LedgerEntry entry);
}

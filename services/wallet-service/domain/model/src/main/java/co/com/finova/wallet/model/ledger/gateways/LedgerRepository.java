package co.com.finova.wallet.model.ledger.gateways;

import co.com.finova.wallet.model.ledger.LedgerEntry;

import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {

    /** Inserts the entry; the ledger is never updated or deleted (spec §2 LedgerEntry). */
    void append(LedgerEntry entry);

    /** The DEBIT entry of a transfer; UNIQUE(transfer_id, type) allows at most one. */
    Optional<LedgerEntry> findDebit(UUID transferId);
}

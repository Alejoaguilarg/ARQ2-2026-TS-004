package co.com.finova.wallet.model.ledger;

import java.util.UUID;

/**
 * The ledger already has an entry of this type for this transfer: UNIQUE(transfer_id, type) (spec §2).
 * The whole local transaction is rolled back, so no balance change survives. It is treated as a
 * technical error: the command is retried and, if it keeps failing, it ends in the DLQ.
 */
public class DuplicateLedgerEntryException extends RuntimeException {

    private final UUID transferId;
    private final LedgerEntryType type;

    public DuplicateLedgerEntryException(UUID transferId, LedgerEntryType type) {
        super("ledger already has a " + type + " entry for transfer " + transferId);
        this.transferId = transferId;
        this.type = type;
    }

    public UUID transferId() {
        return transferId;
    }

    public LedgerEntryType type() {
        return type;
    }
}

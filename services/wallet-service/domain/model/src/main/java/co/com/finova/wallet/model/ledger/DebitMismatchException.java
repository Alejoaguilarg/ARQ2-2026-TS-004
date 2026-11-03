package co.com.finova.wallet.model.ledger;

import java.util.UUID;

/**
 * A credit or a reversal arrived for a transfer without a matching DEBIT in the ledger: there is none,
 * its amount differs, or it was taken from another user's wallet. Applying it would create money, so the
 * transaction is rolled back and the command is treated as a technical error (it ends in the DLQ).
 */
public class DebitMismatchException extends RuntimeException {

    private final UUID transferId;

    public DebitMismatchException(UUID transferId, String detail) {
        super("no matching DEBIT for transfer " + transferId + ": " + detail);
        this.transferId = transferId;
    }

    public UUID transferId() {
        return transferId;
    }
}

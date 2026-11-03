package co.com.finova.wallet.model.settlement;

import co.com.finova.wallet.model.common.Contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Settles a debited transfer exactly once (spec §2 TransferSettlement, ADR 002): the first of the
 * credit and the reversal to claim the row wins, so a transfer is never both credited and reversed.
 */
public record TransferSettlement(UUID transferId, SettlementOutcome outcome, Instant settledAt) {

    public TransferSettlement {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(outcome, "outcome");
        Contract.requireNonNull(settledAt, "settledAt");
    }

    public static TransferSettlement credited(UUID transferId, Instant settledAt) {
        return new TransferSettlement(transferId, SettlementOutcome.CREDITED, settledAt);
    }

    public static TransferSettlement reversed(UUID transferId, Instant settledAt) {
        return new TransferSettlement(transferId, SettlementOutcome.REVERSED, settledAt);
    }
}

package co.com.finova.transfer.usecase.gettransfer;

import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferNotFoundException;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;

import java.util.UUID;

/**
 * GET /transfers/{transferId} (spec §3.9): only the sender and the recipient see a transfer. Anyone else gets the
 * same TransferNotFoundException as for a transfer that does not exist, so its existence is not revealed.
 */
public class GetTransferUseCase {

    private final TransferRepository transfers;

    public GetTransferUseCase(TransferRepository transfers) {
        this.transfers = transfers;
    }

    public Transfer handle(UUID transferId, UUID callerUserId) {
        return transfers.findById(transferId)
                .filter(transfer -> transfer.isVisibleTo(callerUserId))
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }
}

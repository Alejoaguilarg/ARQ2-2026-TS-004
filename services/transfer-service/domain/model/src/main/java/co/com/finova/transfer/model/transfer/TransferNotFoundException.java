package co.com.finova.transfer.model.transfer;

import java.util.UUID;

/** Spec §3.9: the transfer does not exist or the caller is neither its sender nor its recipient → 404. */
public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID transferId) {
        super("transfer " + transferId + " not found");
    }
}

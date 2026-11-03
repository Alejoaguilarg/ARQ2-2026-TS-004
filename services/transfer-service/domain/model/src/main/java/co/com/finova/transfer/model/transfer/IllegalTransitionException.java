package co.com.finova.transfer.model.transfer;

import java.util.UUID;

/** A transition the current status does not allow (spec §2 TransferStatus); always a bug in the caller. */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(UUID transferId, TransferStatus status, String transition) {
        super("transfer " + transferId + " cannot be " + transition + " from " + status);
    }
}

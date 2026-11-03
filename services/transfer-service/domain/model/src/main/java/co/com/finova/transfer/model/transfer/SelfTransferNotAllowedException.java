package co.com.finova.transfer.model.transfer;

/** Spec §3.8: the recipient equals the sender → 400 SELF_TRANSFER_NOT_ALLOWED. */
public class SelfTransferNotAllowedException extends RuntimeException {

    public SelfTransferNotAllowedException() {
        super("recipientUserId must differ from the sender");
    }
}

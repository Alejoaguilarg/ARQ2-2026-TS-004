package co.com.finova.transfer.model.transfer;

/**
 * The insert hit UNIQUE(sender_user_id, idempotency_key) (spec §2): a concurrent request with the same key
 * created the transfer first. The caller answers with that transfer (§3.1.1 defense in depth).
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(Throwable cause) {
        super("a transfer with this sender and Idempotency-Key already exists", cause);
    }
}

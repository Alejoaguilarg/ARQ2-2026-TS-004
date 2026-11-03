package co.com.finova.transfer.model.transfer;

/** Spec §3.1.1: the sender's Idempotency-Key already exists with another requestHash → 422. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used with a different request body");
    }
}

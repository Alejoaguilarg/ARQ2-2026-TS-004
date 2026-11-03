package co.com.finova.transfer.api.error;

/**
 * Spec §3.2 IDEMPOTENCY_KEY_REQUIRED: the Gateway already rejects a missing or non-UUID Idempotency-Key, so this
 * only answers a direct call that skipped it.
 */
public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException() {
        super("Idempotency-Key header must be a UUID");
    }
}

package co.com.finova.gateway.model.idempotency;

public class IdempotencyStoreUnavailableException extends RuntimeException {

    public IdempotencyStoreUnavailableException(Throwable cause) {
        super("Idempotency store unavailable", cause);
    }
}

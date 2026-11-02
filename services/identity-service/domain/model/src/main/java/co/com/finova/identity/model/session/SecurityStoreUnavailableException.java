package co.com.finova.identity.model.session;

// Redis is down: revocations cannot be written, so the operation fails closed (503).
public class SecurityStoreUnavailableException extends RuntimeException {

    public SecurityStoreUnavailableException(Throwable cause) {
        super("Security store unavailable", cause);
    }
}

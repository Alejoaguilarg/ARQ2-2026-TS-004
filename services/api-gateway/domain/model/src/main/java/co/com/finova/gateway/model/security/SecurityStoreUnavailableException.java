package co.com.finova.gateway.model.security;

public class SecurityStoreUnavailableException extends RuntimeException {

    public SecurityStoreUnavailableException(Throwable cause) {
        super("Security store unavailable", cause);
    }
}

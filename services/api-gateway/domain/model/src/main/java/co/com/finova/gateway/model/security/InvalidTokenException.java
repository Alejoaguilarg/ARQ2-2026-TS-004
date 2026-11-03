package co.com.finova.gateway.model.security;

public class InvalidTokenException extends RuntimeException {

    private final boolean expired;

    public InvalidTokenException(String message, boolean expired) {
        super(message);
        this.expired = expired;
    }

    // True only when the token is genuine but its exp has passed.
    public boolean isExpired() {
        return expired;
    }
}

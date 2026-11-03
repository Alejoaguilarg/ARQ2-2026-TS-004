package co.com.finova.transfer.api.error;

import java.util.Map;

/** Spec §3.2 VALIDATION_ERROR: the body or a parameter breaks a rule of §3.8; details name each field. */
public class InvalidRequestException extends RuntimeException {

    private final transient Map<String, Object> details;

    public InvalidRequestException(Map<String, Object> details) {
        super("invalid request: " + details);
        this.details = Map.copyOf(details);
    }

    public Map<String, Object> details() {
        return details;
    }
}

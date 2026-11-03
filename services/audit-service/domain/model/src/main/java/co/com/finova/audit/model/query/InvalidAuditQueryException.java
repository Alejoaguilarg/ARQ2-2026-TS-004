package co.com.finova.audit.model.query;

/** A query or verification parameter breaks the rules of spec §3.10 / §3.11: 400 VALIDATION_ERROR. */
public class InvalidAuditQueryException extends RuntimeException {

    private final String field;
    private final String reason;

    public InvalidAuditQueryException(String field, String reason) {
        super(field + " " + reason);
        this.field = field;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}

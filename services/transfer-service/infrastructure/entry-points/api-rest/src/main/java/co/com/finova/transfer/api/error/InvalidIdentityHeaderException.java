package co.com.finova.transfer.api.error;

/**
 * An identity header that the Gateway must inject (spec §1.5) is missing or is not a UUID. The client
 * never sends it, so this is an internal fault: 500 INTERNAL_ERROR.
 */
public class InvalidIdentityHeaderException extends RuntimeException {

    public InvalidIdentityHeaderException(String header, String value) {
        super(value == null ? header + " header is missing" : header + " header is not a UUID: '" + value + "'");
    }
}

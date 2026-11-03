package co.com.finova.notification.listener;

/**
 * The message does not follow the contract (spec §4.1 envelope and §4.4 payload). Retrying it will
 * not fix it, so it goes straight to the DLQ.
 */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}

package co.com.finova.notification.model.exceptions;

/**
 * The provider did not accept a notification. It is a technical failure: retrying may succeed.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

package co.com.finova.notification.model.notification;

/**
 * Delivery channel of a notification (spec §2: RecipientContact.preferredChannel, NotificationLog.channel).
 */
public enum Channel {
    PUSH,
    EMAIL,
    SMS
}

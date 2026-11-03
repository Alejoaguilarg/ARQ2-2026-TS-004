package co.com.finova.notification.model.notification;

/**
 * Result of the last delivery attempt of a notification (spec §2: NotificationLog.status).
 * A SENT notification is never sent again.
 */
public enum NotificationStatus {
    SENT,
    FAILED
}

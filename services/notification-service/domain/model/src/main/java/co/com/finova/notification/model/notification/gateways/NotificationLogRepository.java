package co.com.finova.notification.model.notification.gateways;

import co.com.finova.notification.model.notification.NotificationLog;
import co.com.finova.notification.model.notification.TransferEventType;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port: one row per notification (table notification_log,
 * UNIQUE(transfer_id, event_type, user_id)).
 */
public interface NotificationLogRepository {

    Optional<NotificationLog> find(UUID transferId, TransferEventType eventType, UUID userId);

    /** Inserts the row, or updates it when a row with the same notificationId already exists. */
    void save(NotificationLog notificationLog);
}

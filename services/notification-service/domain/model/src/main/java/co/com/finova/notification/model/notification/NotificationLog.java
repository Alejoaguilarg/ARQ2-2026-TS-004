package co.com.finova.notification.model.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per notification (spec §2: NotificationLog). The natural key is
 * (transferId, eventType, userId); a retry updates the same row (status and attempts).
 */
public record NotificationLog(
        UUID notificationId,
        UUID transferId,
        TransferEventType eventType,
        UUID userId,
        Channel channel,
        NotificationStatus status,
        int attempts,
        Instant createdAt,
        Instant updatedAt) {

    public NotificationLog {
        Objects.requireNonNull(notificationId, "notificationId is required");
        Objects.requireNonNull(transferId, "transferId is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(channel, "channel is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
    }

    public static NotificationLog firstAttempt(UUID notificationId, UUID transferId, TransferEventType eventType,
                                               UUID userId, Channel channel, NotificationStatus status,
                                               Instant now) {
        return new NotificationLog(notificationId, transferId, eventType, userId, channel, status, 1, now, now);
    }

    /** Same notification after one more delivery attempt. */
    public NotificationLog nextAttempt(Channel channelUsed, NotificationStatus newStatus, Instant now) {
        return new NotificationLog(notificationId, transferId, eventType, userId, channelUsed, newStatus,
                attempts + 1, createdAt, now);
    }

    public boolean isSent() {
        return status == NotificationStatus.SENT;
    }
}

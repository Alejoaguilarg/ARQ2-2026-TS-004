package co.com.finova.notification.model.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * A notification ready to be handed to the external Email / SMS / Push provider (spec §1.1).
 *
 * @param to            email address, phone number or push token, depending on the channel
 * @param correlationId correlationId of the transfer; may be null
 */
public record Notification(
        UUID notificationId,
        Channel channel,
        String to,
        TransferEventType eventType,
        UUID transferId,
        String amount,
        String currency,
        String reason,
        String subject,
        String message,
        String correlationId) {

    public Notification {
        Objects.requireNonNull(notificationId, "notificationId is required");
        Objects.requireNonNull(channel, "channel is required");
        Objects.requireNonNull(to, "to is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(transferId, "transferId is required");
        Objects.requireNonNull(subject, "subject is required");
        Objects.requireNonNull(message, "message is required");
    }
}

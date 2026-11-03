package co.com.finova.notification.model.notification;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Final outcome of a transfer published by the Transfer Service
 * (spec §4.4: TransferCompleted, TransferRejected, TransferReversed).
 *
 * @param reason        rejection or reversal reason; null for TransferCompleted
 * @param correlationId envelope correlationId, used to trace the transfer across services; may be null
 */
public record TransferOutcome(
        TransferEventType type,
        UUID transferId,
        UUID senderUserId,
        UUID recipientUserId,
        String amount,
        String currency,
        String reason,
        String correlationId) {

    public TransferOutcome {
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(transferId, "transferId is required");
        Objects.requireNonNull(senderUserId, "senderUserId is required");
        Objects.requireNonNull(recipientUserId, "recipientUserId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
    }

    /**
     * Users that must be notified (spec §4.9): a completed transfer notifies the sender and the
     * recipient; a rejected or reversed transfer only notifies the sender.
     */
    public List<UUID> usersToNotify() {
        if (type == TransferEventType.TRANSFER_COMPLETED) {
            return List.of(senderUserId, recipientUserId);
        }
        return List.of(senderUserId);
    }

    public boolean isSender(UUID userId) {
        return senderUserId.equals(userId);
    }
}

package co.com.finova.notification.model.notification;

import java.util.Arrays;
import java.util.Optional;

/**
 * Transfer outcome events that Notification consumes (spec §4.3 and §4.9).
 */
public enum TransferEventType {
    TRANSFER_COMPLETED("TransferCompleted"),
    TRANSFER_REJECTED("TransferRejected"),
    TRANSFER_REVERSED("TransferReversed");

    private final String messageType;

    TransferEventType(String messageType) {
        this.messageType = messageType;
    }

    /**
     * Name of the event as it travels in the envelope {@code type} field (spec §4.1)
     * and as it is stored in {@code notification_log.event_type}.
     */
    public String messageType() {
        return messageType;
    }

    public static Optional<TransferEventType> fromMessageType(String messageType) {
        return Arrays.stream(values())
                .filter(type -> type.messageType.equals(messageType))
                .findFirst();
    }
}

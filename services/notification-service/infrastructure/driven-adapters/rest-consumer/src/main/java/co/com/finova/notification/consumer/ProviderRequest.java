package co.com.finova.notification.consumer;

import co.com.finova.notification.model.notification.Notification;

/**
 * JSON body sent to {@code POST {base-url}/{push|email|sms}}.
 */
public record ProviderRequest(
        String notificationId,
        String channel,
        String to,
        String subject,
        String message,
        String eventType,
        String transferId,
        String amount,
        String currency,
        String reason) {

    static ProviderRequest from(Notification notification) {
        return new ProviderRequest(
                notification.notificationId().toString(),
                notification.channel().name(),
                notification.to(),
                notification.subject(),
                notification.message(),
                notification.eventType().messageType(),
                notification.transferId().toString(),
                notification.amount(),
                notification.currency(),
                notification.reason());
    }
}

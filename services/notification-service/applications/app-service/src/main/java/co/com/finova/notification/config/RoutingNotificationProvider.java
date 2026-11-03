package co.com.finova.notification.config;

import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;

import java.util.EnumMap;
import java.util.Map;

/**
 * Chooses the adapter for each channel: a channel with its own adapter (for example EMAIL through
 * SMTP) uses it; every other channel goes to the default provider (the HTTP provider mock).
 * The use case only sees the {@link NotificationProvider} port.
 */
public class RoutingNotificationProvider implements NotificationProvider {

    private final NotificationProvider defaultProvider;
    private final Map<Channel, NotificationProvider> providersByChannel;

    public RoutingNotificationProvider(NotificationProvider defaultProvider,
                                       Map<Channel, NotificationProvider> providersByChannel) {
        this.defaultProvider = defaultProvider;
        this.providersByChannel = providersByChannel.isEmpty()
                ? new EnumMap<>(Channel.class)
                : new EnumMap<>(providersByChannel);
    }

    @Override
    public void send(Notification notification) {
        providersByChannel.getOrDefault(notification.channel(), defaultProvider).send(notification);
    }
}

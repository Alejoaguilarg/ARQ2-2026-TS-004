package co.com.finova.notification.config;

import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.TransferEventType;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RoutingNotificationProviderTest {

    private final NotificationProvider http = mock(NotificationProvider.class);
    private final NotificationProvider smtp = mock(NotificationProvider.class);

    @Test
    void withoutOverridesEveryChannelGoesToTheDefaultProvider() {
        RoutingNotificationProvider provider = new RoutingNotificationProvider(http, Map.of());
        Notification email = notification(Channel.EMAIL);
        Notification sms = notification(Channel.SMS);

        provider.send(email);
        provider.send(sms);

        verify(http).send(email);
        verify(http).send(sms);
    }

    @Test
    void emailCanGoThroughItsOwnAdapter() {
        RoutingNotificationProvider provider = new RoutingNotificationProvider(http, Map.of(Channel.EMAIL, smtp));
        Notification email = notification(Channel.EMAIL);
        Notification push = notification(Channel.PUSH);

        provider.send(email);
        provider.send(push);

        verify(smtp).send(email);
        verify(http).send(push);
    }

    @Test
    void smtpIsNotUsedForOtherChannels() {
        RoutingNotificationProvider provider = new RoutingNotificationProvider(http, Map.of(Channel.EMAIL, smtp));

        provider.send(notification(Channel.SMS));

        verifyNoInteractions(smtp);
    }

    private static Notification notification(Channel channel) {
        return new Notification(UUID.randomUUID(), channel, "to", TransferEventType.TRANSFER_COMPLETED,
                UUID.randomUUID(), "1.00", "COP", null, "subject", "message", null);
    }
}

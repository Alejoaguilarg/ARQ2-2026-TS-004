package co.com.finova.notification.consumer;

import co.com.finova.notification.model.exceptions.NotificationDeliveryException;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.TransferEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestConsumerConfigTest {

    @Test
    void anUnreachableProviderIsADeliveryFailureWithinTheTimeout() {
        // Nothing listens on port 1: the request fails fast instead of blocking the consumer
        ProviderProperties properties = new ProviderProperties("http://127.0.0.1:1", Duration.ofMillis(300),
                Duration.ofMillis(300));
        HttpNotificationProvider provider = new RestConsumerConfig().httpNotificationProvider(properties);
        Notification notification = new Notification(UUID.randomUUID(), Channel.PUSH, "token",
                TransferEventType.TRANSFER_COMPLETED, UUID.randomUUID(), "1.00", "COP", null, "s", "m", null);

        assertThatThrownBy(() -> provider.send(notification)).isInstanceOf(NotificationDeliveryException.class);
    }
}

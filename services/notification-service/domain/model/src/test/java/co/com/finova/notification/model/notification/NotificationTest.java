package co.com.finova.notification.model.notification;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class NotificationTest {

    @Test
    void keepsWhatTheProviderNeeds() {
        UUID id = UUID.randomUUID();
        Notification notification = new Notification(id, Channel.SMS, "+573001112233",
                TransferEventType.TRANSFER_REJECTED, UUID.randomUUID(), "1.00", "COP", "INSUFFICIENT_FUNDS",
                "Subject", "Message", null);

        assertThat(notification.notificationId()).isEqualTo(id);
        assertThat(notification.to()).isEqualTo("+573001112233");
        assertThat(notification.correlationId()).isNull();
    }

    @Test
    void requiresTheAddressAndTheText() {
        assertThatNullPointerException().isThrownBy(() -> new Notification(UUID.randomUUID(), Channel.EMAIL,
                null, TransferEventType.TRANSFER_COMPLETED, UUID.randomUUID(), "1.00", "COP", null, "s", "m", null));
        assertThatNullPointerException().isThrownBy(() -> new Notification(UUID.randomUUID(), Channel.EMAIL,
                "a@b.test", TransferEventType.TRANSFER_COMPLETED, UUID.randomUUID(), "1.00", "COP", null, "s", null,
                null));
    }
}

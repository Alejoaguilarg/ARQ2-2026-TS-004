package co.com.finova.notification.model.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NotificationLogTest {

    private static final Instant T0 = Instant.parse("2026-11-02T15:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-02T15:00:05Z");

    @Test
    void firstAttemptStartsAtOne() {
        NotificationLog log = NotificationLog.firstAttempt(UUID.randomUUID(), UUID.randomUUID(),
                TransferEventType.TRANSFER_COMPLETED, UUID.randomUUID(), Channel.EMAIL,
                NotificationStatus.FAILED, T0);

        assertThat(log.attempts()).isEqualTo(1);
        assertThat(log.createdAt()).isEqualTo(T0);
        assertThat(log.updatedAt()).isEqualTo(T0);
        assertThat(log.isSent()).isFalse();
    }

    @Test
    void nextAttemptUpdatesTheSameNotification() {
        NotificationLog first = NotificationLog.firstAttempt(UUID.randomUUID(), UUID.randomUUID(),
                TransferEventType.TRANSFER_COMPLETED, UUID.randomUUID(), Channel.EMAIL,
                NotificationStatus.FAILED, T0);

        NotificationLog second = first.nextAttempt(Channel.EMAIL, NotificationStatus.SENT, T1);

        assertThat(second.notificationId()).isEqualTo(first.notificationId());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.createdAt()).isEqualTo(T0);
        assertThat(second.updatedAt()).isEqualTo(T1);
        assertThat(second.isSent()).isTrue();
    }

    @Test
    void attemptsMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NotificationLog(UUID.randomUUID(),
                UUID.randomUUID(), TransferEventType.TRANSFER_COMPLETED, UUID.randomUUID(), Channel.EMAIL,
                NotificationStatus.SENT, 0, T0, T0));
    }
}

package co.com.finova.notification.model.exceptions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionsTest {

    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void deliveryExceptionKeepsItsCause() {
        IllegalStateException cause = new IllegalStateException("503");

        assertThat(new NotificationDeliveryException("down", cause)).hasMessage("down").hasCause(cause);
        assertThat(new NotificationDeliveryException("down")).hasMessage("down").hasNoCause();
    }

    @Test
    void unreachableExceptionNamesTheUsers() {
        List<UUID> users = new ArrayList<>(List.of(USER));
        RecipientUnreachableException exception = new RecipientUnreachableException(TRANSFER, users);
        users.clear();

        assertThat(exception.getUserIds()).containsExactly(USER);
        assertThat(exception.getMessage()).contains(USER.toString()).contains(TRANSFER.toString());
    }

    @Test
    void pendingExceptionNamesTheUsers() {
        PendingNotificationsException exception = new PendingNotificationsException(TRANSFER, List.of(USER));

        assertThat(exception.getUserIds()).containsExactly(USER);
        assertThat(exception.getMessage()).contains(USER.toString()).contains(TRANSFER.toString());
    }
}

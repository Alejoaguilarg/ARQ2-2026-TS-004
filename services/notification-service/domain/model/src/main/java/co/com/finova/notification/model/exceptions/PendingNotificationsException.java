package co.com.finova.notification.model.exceptions;

import java.util.List;
import java.util.UUID;

/**
 * At least one notification of a transfer outcome could not be delivered. The message must be
 * retried; users already notified are skipped on the retry.
 */
public class PendingNotificationsException extends RuntimeException {

    private final transient List<UUID> userIds;

    public PendingNotificationsException(UUID transferId, List<UUID> userIds) {
        super("Notifications pending for users " + userIds + " (transfer " + transferId + ")");
        this.userIds = List.copyOf(userIds);
    }

    public List<UUID> getUserIds() {
        return userIds;
    }
}

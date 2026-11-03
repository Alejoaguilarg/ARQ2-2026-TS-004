package co.com.finova.notification.model.exceptions;

import java.util.List;
import java.util.UUID;

/**
 * A user has no contact in recipient_contacts, or the contact has no address for its preferred
 * channel. It is a data problem: retrying the message will not fix it.
 */
public class RecipientUnreachableException extends RuntimeException {

    private final transient List<UUID> userIds;

    public RecipientUnreachableException(UUID transferId, List<UUID> userIds) {
        super("No usable contact data for users " + userIds + " (transfer " + transferId + ")");
        this.userIds = List.copyOf(userIds);
    }

    public List<UUID> getUserIds() {
        return userIds;
    }
}

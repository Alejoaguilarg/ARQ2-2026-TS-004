package co.com.finova.notification.model.notification;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Contact data of a user (spec §2: RecipientContact). It is a copy of the data owned by Identity,
 * provisioned with the initial data set; Notification never calls Identity at runtime.
 */
public record RecipientContact(
        UUID userId,
        String email,
        String phone,
        String pushToken,
        Channel preferredChannel) {

    public RecipientContact {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(preferredChannel, "preferredChannel is required");
    }

    /**
     * Address to use for the given channel: the email for EMAIL, the phone for SMS and the push
     * token for PUSH. Empty when the contact has no value for that channel.
     */
    public Optional<String> addressFor(Channel channel) {
        String address = switch (channel) {
            case EMAIL -> email;
            case SMS -> phone;
            case PUSH -> pushToken;
        };
        return Optional.ofNullable(address).filter(value -> !value.isBlank());
    }
}

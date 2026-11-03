package co.com.finova.notification.model.notification.gateways;

import co.com.finova.notification.model.notification.RecipientContact;

import java.util.Optional;
import java.util.UUID;

/**
 * Output port: contact data of the users (table recipient_contacts).
 */
public interface RecipientContactRepository {

    Optional<RecipientContact> findByUserId(UUID userId);
}

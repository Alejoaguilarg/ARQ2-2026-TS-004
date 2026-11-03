package co.com.finova.notification.usecase.notifytransferoutcome;

import co.com.finova.notification.model.exceptions.NotificationDeliveryException;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.NotificationLog;
import co.com.finova.notification.model.notification.RecipientContact;
import co.com.finova.notification.model.notification.TransferEventType;
import co.com.finova.notification.model.notification.gateways.NotificationLogRepository;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;
import co.com.finova.notification.model.notification.gateways.RecipientContactRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory implementations of the output ports. The use case works with them exactly as with the
 * real adapters (Liskov), so its rules are tested without Spring, a database or a broker.
 */
final class InMemoryAdapters {

    private InMemoryAdapters() {
    }

    static final class Contacts implements RecipientContactRepository {
        private final Map<UUID, RecipientContact> contacts = new HashMap<>();

        Contacts add(RecipientContact contact) {
            contacts.put(contact.userId(), contact);
            return this;
        }

        @Override
        public Optional<RecipientContact> findByUserId(UUID userId) {
            return Optional.ofNullable(contacts.get(userId));
        }
    }

    static final class Logs implements NotificationLogRepository {
        private final Map<UUID, NotificationLog> rows = new HashMap<>();

        @Override
        public Optional<NotificationLog> find(UUID transferId, TransferEventType eventType, UUID userId) {
            return rows.values().stream()
                    .filter(row -> row.transferId().equals(transferId)
                            && row.eventType() == eventType
                            && row.userId().equals(userId))
                    .findFirst();
        }

        @Override
        public void save(NotificationLog notificationLog) {
            rows.put(notificationLog.notificationId(), notificationLog);
        }

        NotificationLog of(UUID transferId, TransferEventType eventType, UUID userId) {
            return find(transferId, eventType, userId).orElseThrow();
        }

        int size() {
            return rows.size();
        }
    }

    /** Records what was sent; fails for the addresses marked as failing. */
    static final class Provider implements NotificationProvider {
        private final List<Notification> sent = new ArrayList<>();
        private final Set<String> failingAddresses = new HashSet<>();

        Provider failFor(String address) {
            failingAddresses.add(address);
            return this;
        }

        Provider recover(String address) {
            failingAddresses.remove(address);
            return this;
        }

        @Override
        public void send(Notification notification) {
            if (failingAddresses.contains(notification.to())) {
                throw new NotificationDeliveryException("provider unavailable for " + notification.to());
            }
            sent.add(notification);
        }

        List<Notification> sent() {
            return sent;
        }
    }
}

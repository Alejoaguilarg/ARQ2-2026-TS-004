package co.com.finova.notification.jpa;

import co.com.finova.notification.jpa.entity.NotificationLogEntity;
import co.com.finova.notification.model.notification.NotificationLog;
import co.com.finova.notification.model.notification.TransferEventType;
import co.com.finova.notification.model.notification.gateways.NotificationLogRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven adapter: implements the {@link NotificationLogRepository} port with Spring Data JPA.
 */
@Repository
public class NotificationLogRepositoryAdapter implements NotificationLogRepository {

    private final NotificationLogJpaRepository repository;

    public NotificationLogRepositoryAdapter(NotificationLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<NotificationLog> find(UUID transferId, TransferEventType eventType, UUID userId) {
        return repository.findByTransferIdAndEventTypeAndUserId(transferId, eventType.messageType(), userId)
                .map(NotificationLogRepositoryAdapter::toDomain);
    }

    @Override
    public void save(NotificationLog notificationLog) {
        repository.save(toEntity(notificationLog));
    }

    static NotificationLog toDomain(NotificationLogEntity entity) {
        TransferEventType eventType = TransferEventType.fromMessageType(entity.getEventType())
                .orElseThrow(() -> new IllegalStateException("Unknown event_type in notification_log: "
                        + entity.getEventType()));
        return new NotificationLog(entity.getNotificationId(), entity.getTransferId(), eventType,
                entity.getUserId(), entity.getChannel(), entity.getStatus(), entity.getAttempts(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    static NotificationLogEntity toEntity(NotificationLog log) {
        NotificationLogEntity entity = new NotificationLogEntity();
        entity.setNotificationId(log.notificationId());
        entity.setTransferId(log.transferId());
        entity.setEventType(log.eventType().messageType());
        entity.setUserId(log.userId());
        entity.setChannel(log.channel());
        entity.setStatus(log.status());
        entity.setAttempts(log.attempts());
        entity.setCreatedAt(log.createdAt());
        entity.setUpdatedAt(log.updatedAt());
        return entity;
    }
}

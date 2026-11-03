package co.com.finova.notification.jpa;

import co.com.finova.notification.jpa.entity.NotificationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationLogJpaRepository extends JpaRepository<NotificationLogEntity, UUID> {

    Optional<NotificationLogEntity> findByTransferIdAndEventTypeAndUserId(UUID transferId, String eventType,
                                                                         UUID userId);
}

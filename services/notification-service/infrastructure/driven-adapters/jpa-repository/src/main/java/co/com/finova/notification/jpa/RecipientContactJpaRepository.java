package co.com.finova.notification.jpa;

import co.com.finova.notification.jpa.entity.RecipientContactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecipientContactJpaRepository extends JpaRepository<RecipientContactEntity, UUID> {
}

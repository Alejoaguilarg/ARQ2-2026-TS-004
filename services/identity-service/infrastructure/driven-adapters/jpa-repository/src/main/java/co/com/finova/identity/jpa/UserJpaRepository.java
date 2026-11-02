package co.com.finova.identity.jpa;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends Repository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findById(UUID userId);
}

package co.com.finova.wallet.jpa;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletJpaRepository extends CrudRepository<WalletEntity, UUID> {

    Optional<WalletEntity> findByUserId(UUID userId);
}

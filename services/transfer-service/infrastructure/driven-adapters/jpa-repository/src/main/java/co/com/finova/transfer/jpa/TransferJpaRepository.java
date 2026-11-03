package co.com.finova.transfer.jpa;

import org.springframework.data.repository.CrudRepository;
import java.util.Optional;

public interface TransferJpaRepository extends CrudRepository<TransferJpaEntity, String> {
    Optional<TransferJpaEntity> findByIdempotencyKey(String idempotencyKey);
}

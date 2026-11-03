package co.com.finova.transfer.jpa;

import org.springframework.data.repository.CrudRepository;

public interface TransferJpaRepository extends CrudRepository<TransferJpaEntity, String> {
    TransferJpaEntity findByTransferId(String transferId);
    TransferJpaEntity findByIdempotencyKey(String idempotencyKey);
}

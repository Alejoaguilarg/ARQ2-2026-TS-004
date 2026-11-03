package co.com.finova.transfer.model;

import java.util.Optional;

public interface TransferRepository {
    void save(Transfer transfer);
    Optional<Transfer> findByTransferId(String transferId);
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}

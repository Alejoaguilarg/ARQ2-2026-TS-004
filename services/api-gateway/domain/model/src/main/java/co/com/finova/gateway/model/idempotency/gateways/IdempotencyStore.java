package co.com.finova.gateway.model.idempotency.gateways;

import co.com.finova.gateway.model.idempotency.IdempotencyRecord;

import java.util.Optional;

// Every method throws IdempotencyStoreUnavailableException when the store cannot be reached.
public interface IdempotencyStore {

    // Atomic create-if-absent; false when the key already exists.
    boolean createIfAbsent(String userId, String idempotencyKey, IdempotencyRecord record);

    Optional<IdempotencyRecord> find(String userId, String idempotencyKey);

    void save(String userId, String idempotencyKey, IdempotencyRecord record);

    void delete(String userId, String idempotencyKey);
}

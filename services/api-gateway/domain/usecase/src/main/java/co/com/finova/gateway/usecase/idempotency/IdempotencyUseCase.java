package co.com.finova.gateway.usecase.idempotency;

import co.com.finova.gateway.model.idempotency.IdempotencyRecord;
import co.com.finova.gateway.model.idempotency.gateways.IdempotencyStore;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class IdempotencyUseCase {

    public sealed interface Decision {
        record Proceed() implements Decision { }
        record InProgress() implements Decision { }
        record KeyReused() implements Decision { }
        record Replay(int httpStatus, String body) implements Decision { }
    }

    private final IdempotencyStore store;

    // spec.md §3.1.1, checks in the spec's order.
    public Decision begin(String userId, String idempotencyKey, String requestHash) {
        // Two attempts: the key can expire between a failed create and the read.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (store.createIfAbsent(userId, idempotencyKey, IdempotencyRecord.inProgress(requestHash))) {
                return new Decision.Proceed();
            }
            Optional<IdempotencyRecord> existing = store.find(userId, idempotencyKey);
            if (existing.isPresent()) {
                return decide(existing.get(), requestHash);
            }
        }
        return new Decision.InProgress();
    }

    // Upstream answered: keep 2xx/4xx for replay, drop 5xx so the client can retry with the same key.
    public void finish(String userId, String idempotencyKey, String requestHash, int httpStatus, String body) {
        if (httpStatus >= 500) {
            store.delete(userId, idempotencyKey);
        } else {
            store.save(userId, idempotencyKey, IdempotencyRecord.completed(requestHash, httpStatus, body));
        }
    }

    // Upstream did not answer.
    public void abort(String userId, String idempotencyKey) {
        store.delete(userId, idempotencyKey);
    }

    private static Decision decide(IdempotencyRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            return new Decision.KeyReused();
        }
        if (existing.state() == IdempotencyRecord.State.IN_PROGRESS) {
            return new Decision.InProgress();
        }
        return new Decision.Replay(existing.httpStatus(), existing.body());
    }
}

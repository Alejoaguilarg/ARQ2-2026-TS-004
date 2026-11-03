package co.com.finova.gateway.model.idempotency;

// spec.md §2.1 idem:{userId}:{idempotencyKey}; httpStatus and body are set only once COMPLETED.
public record IdempotencyRecord(State state, String requestHash, Integer httpStatus, String body) {

    public enum State { IN_PROGRESS, COMPLETED }

    public static IdempotencyRecord inProgress(String requestHash) {
        return new IdempotencyRecord(State.IN_PROGRESS, requestHash, null, null);
    }

    public static IdempotencyRecord completed(String requestHash, int httpStatus, String body) {
        return new IdempotencyRecord(State.COMPLETED, requestHash, httpStatus, body);
    }
}

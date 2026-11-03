package co.com.finova.gateway.model.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * A POST /transfers the Gateway answered itself, or that failed upstream (spec §3.1 step 6, §4.8).
 * It is always source GATEWAY and stage REQUEST. transferId, userId and idempotencyKey are null when unknown.
 */
public record GatewayAttempt(UUID eventId, Outcome outcome, String reason, String transferId, String userId,
                             String idempotencyKey, int httpStatus, String correlationId, Instant occurredAt) {

    public enum Outcome { REJECTED, DUPLICATE, FAILED }
}

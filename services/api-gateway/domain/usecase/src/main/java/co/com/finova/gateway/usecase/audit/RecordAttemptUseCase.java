package co.com.finova.gateway.usecase.audit;

import co.com.finova.gateway.model.audit.GatewayAttempt;
import co.com.finova.gateway.model.audit.GatewayAttempt.Outcome;
import co.com.finova.gateway.model.audit.gateways.AttemptPublisher;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// spec.md §3.1 step 6 and §4.8: what the Gateway records for each POST /transfers.
@RequiredArgsConstructor
public class RecordAttemptUseCase {

    /**
     * How a POST /transfers was answered. gatewayCode is the ErrorResponse code when the Gateway answered
     * itself, replayed tells a stored response replayed by the Gateway; both empty means Transfer answered.
     */
    public record Answer(int httpStatus, String gatewayCode, boolean replayed, String transferId, String userId,
                         String idempotencyKey, String correlationId) {
    }

    private final AttemptPublisher publisher;

    public void record(Answer answer) {
        Outcome outcome;
        String reason;
        if (answer.replayed()) {
            outcome = Outcome.DUPLICATE;
            reason = "DUPLICATE_REPLAYED";
        } else if (answer.gatewayCode() != null) {
            reason = switch (answer.gatewayCode()) {
                case "IDEMPOTENCY_KEY_REQUIRED" -> "MISSING_KEY";
                case "IDEMPOTENCY_KEY_REUSED" -> "KEY_REUSED";
                case "IDEMPOTENCY_IN_PROGRESS" -> "IN_PROGRESS_CONFLICT";
                default -> answer.gatewayCode();
            };
            outcome = switch (reason) {
                case "IN_PROGRESS_CONFLICT" -> Outcome.DUPLICATE;
                case "UPSTREAM_UNAVAILABLE" -> Outcome.FAILED;
                default -> Outcome.REJECTED;
            };
        } else if (answer.httpStatus() >= 500) {
            outcome = Outcome.FAILED;
            reason = "INTERNAL_ERROR";
        } else {
            // Transfer answered 2xx or 4xx: Transfer records it itself (ACCEPTED, DUPLICATE_TRANSFER, REJECTED).
            return;
        }
        publisher.publish(new GatewayAttempt(UUID.randomUUID(), outcome, reason, answer.transferId(),
                answer.userId(), answer.idempotencyKey(), answer.httpStatus(), answer.correlationId(), Instant.now()));
    }
}

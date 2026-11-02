package co.com.finova.audit.model.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AttemptRecorded(
        UUID eventId,
        AuditSource source,
        AuditStage stage,
        AuditOutcome outcome,
        String reason,
        UUID transferId,
        UUID userId,
        String idempotencyKey,
        BigDecimal amount,
        Integer httpStatus,
        UUID correlationId,
        Instant occurredAt
) {
}
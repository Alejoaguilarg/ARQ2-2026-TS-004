package co.com.finova.audit.model.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuditRecord(
        long seq,
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
        Instant occurredAt,
        Instant recordedAt,
        String prevHash,
        String hash
) {
}
package co.com.finova.audit.model.audit;

import java.util.UUID;

public record AuditEventId(
        UUID eventId,
        long seq
) {
}
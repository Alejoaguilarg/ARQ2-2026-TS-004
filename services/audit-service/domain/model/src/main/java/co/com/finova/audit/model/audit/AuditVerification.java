package co.com.finova.audit.model.audit;

import java.time.Instant;

public record AuditVerification(
        boolean valid,
        long checkedRecords,
        Long firstInvalidSeq,
        Instant verifiedAt
) {
}
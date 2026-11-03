package co.com.finova.audit.api.dto;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.record.AuditRecord;

import java.util.Objects;

/** AuditRecord as JSON (spec §2, §3.10). Nullable fields are written as null, never omitted. */
public record AuditRecordResponse(long seq, String eventId, String source, String stage, String outcome,
                                  String reason, String transferId, String userId, String idempotencyKey,
                                  String amount, Integer httpStatus, String correlationId, String occurredAt,
                                  String recordedAt, String prevHash, String hash) {

    public static AuditRecordResponse from(AuditRecord record) {
        AttemptRecorded attempt = record.attempt();
        return new AuditRecordResponse(record.seq(), attempt.eventId().toString(), attempt.source().name(),
                attempt.stage().name(), attempt.outcome().name(), attempt.reason(),
                Objects.toString(attempt.transferId(), null), Objects.toString(attempt.userId(), null),
                attempt.idempotencyKey(), attempt.amount(), attempt.statusCode(),
                attempt.correlationId().toString(), attempt.occurredAt().toString(),
                record.recordedAt().toString(), record.prevHash(), record.hash());
    }
}

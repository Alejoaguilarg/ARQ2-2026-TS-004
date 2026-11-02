package co.com.finova.audit.usecase.recordaudit;

import co.com.finova.audit.model.audit.AttemptRecorded;
import co.com.finova.audit.model.audit.AuditEventId;
import co.com.finova.audit.model.audit.AuditRecord;
import co.com.finova.audit.model.audit.gateways.AuditEventIdRepository;
import co.com.finova.audit.model.audit.gateways.AuditHashGenerator;
import co.com.finova.audit.model.audit.gateways.AuditRecordRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class RecordAuditUseCase {

    private static final String GENESIS_HASH = "0".repeat(64);

    private final AuditRecordRepository auditRecordRepository;
    private final AuditEventIdRepository auditEventIdRepository;
    private final AuditHashGenerator auditHashGenerator;
    private final Clock clock;

    public RecordAuditUseCase(
            AuditRecordRepository auditRecordRepository,
            AuditEventIdRepository auditEventIdRepository,
            AuditHashGenerator auditHashGenerator,
            Clock clock
    ) {
        this.auditRecordRepository = Objects.requireNonNull(
                auditRecordRepository,
                "auditRecordRepository is required"
        );
        this.auditEventIdRepository = Objects.requireNonNull(
                auditEventIdRepository,
                "auditEventIdRepository is required"
        );
        this.auditHashGenerator = Objects.requireNonNull(
                auditHashGenerator,
                "auditHashGenerator is required"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock is required"
        );
    }

    public boolean execute(AttemptRecorded attempt) {
        validate(attempt);

        if (auditEventIdRepository.exists(attempt.eventId())) {
            return false;
        }

        long sequence = auditRecordRepository.nextSequence();

        String previousHash = auditRecordRepository.findLast()
                .map(AuditRecord::hash)
                .orElse(GENESIS_HASH);

        Instant recordedAt = clock.instant();

        AuditRecord recordWithoutHash = new AuditRecord(
                sequence,
                attempt.eventId(),
                attempt.source(),
                attempt.stage(),
                attempt.outcome(),
                attempt.reason(),
                attempt.transferId(),
                attempt.userId(),
                attempt.idempotencyKey(),
                attempt.amount(),
                attempt.httpStatus(),
                attempt.correlationId(),
                attempt.occurredAt(),
                recordedAt,
                previousHash,
                null
        );

        String hash = auditHashGenerator.generate(recordWithoutHash);

        AuditRecord finalRecord = new AuditRecord(
                recordWithoutHash.seq(),
                recordWithoutHash.eventId(),
                recordWithoutHash.source(),
                recordWithoutHash.stage(),
                recordWithoutHash.outcome(),
                recordWithoutHash.reason(),
                recordWithoutHash.transferId(),
                recordWithoutHash.userId(),
                recordWithoutHash.idempotencyKey(),
                recordWithoutHash.amount(),
                recordWithoutHash.httpStatus(),
                recordWithoutHash.correlationId(),
                recordWithoutHash.occurredAt(),
                recordWithoutHash.recordedAt(),
                recordWithoutHash.prevHash(),
                hash
        );

        auditRecordRepository.save(finalRecord);

        auditEventIdRepository.save(
                new AuditEventId(
                        attempt.eventId(),
                        sequence
                )
        );

        return true;
    }

    private void validate(AttemptRecorded attempt) {
        Objects.requireNonNull(attempt, "attempt is required");
        Objects.requireNonNull(attempt.eventId(), "eventId is required");
        Objects.requireNonNull(attempt.source(), "source is required");
        Objects.requireNonNull(attempt.stage(), "stage is required");
        Objects.requireNonNull(attempt.outcome(), "outcome is required");
        Objects.requireNonNull(
                attempt.correlationId(),
                "correlationId is required"
        );
        Objects.requireNonNull(
                attempt.occurredAt(),
                "occurredAt is required"
        );

        if (attempt.amount() != null
                && attempt.amount().signum() <= 0) {
            throw new IllegalArgumentException(
                    "amount must be greater than zero"
            );
        }

        if (attempt.httpStatus() != null
                && (attempt.httpStatus() < 100
                || attempt.httpStatus() > 599)) {
            throw new IllegalArgumentException(
                    "httpStatus must be between 100 and 599"
            );
        }
    }
}
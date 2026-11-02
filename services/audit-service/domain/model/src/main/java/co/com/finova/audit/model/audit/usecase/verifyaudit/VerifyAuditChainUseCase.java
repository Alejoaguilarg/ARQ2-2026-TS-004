package co.com.finova.audit.usecase.verifyaudit;

import co.com.finova.audit.model.audit.AuditRecord;
import co.com.finova.audit.model.audit.AuditVerification;
import co.com.finova.audit.model.audit.gateways.AuditHashGenerator;
import co.com.finova.audit.model.audit.gateways.AuditRecordRepository;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class VerifyAuditChainUseCase {

    private static final String GENESIS_HASH = "0".repeat(64);

    private final AuditRecordRepository auditRecordRepository;
    private final AuditHashGenerator auditHashGenerator;
    private final Clock clock;

    public VerifyAuditChainUseCase(
            AuditRecordRepository auditRecordRepository,
            AuditHashGenerator auditHashGenerator,
            Clock clock
    ) {
        this.auditRecordRepository = Objects.requireNonNull(
                auditRecordRepository,
                "auditRecordRepository is required"
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

    public AuditVerification execute(
            Long fromSequence,
            Long toSequence
    ) {
        validateRange(fromSequence, toSequence);

        List<AuditRecord> records =
                auditRecordRepository.findBySequenceRange(
                        fromSequence,
                        toSequence
                );

        if (records.isEmpty()) {
            return new AuditVerification(
                    true,
                    0,
                    null,
                    clock.instant()
            );
        }

        List<AuditRecord> orderedRecords = records.stream()
                .sorted(Comparator.comparingLong(AuditRecord::seq))
                .toList();

        String expectedPreviousHash =
                resolveExpectedPreviousHash(orderedRecords.getFirst());

        long checkedRecords = 0;

        for (AuditRecord auditRecord : orderedRecords) {
            checkedRecords++;

            if (!expectedPreviousHash.equals(
                    auditRecord.prevHash()
            )) {
                return invalid(
                        checkedRecords,
                        auditRecord.seq()
                );
            }

            String calculatedHash =
                    auditHashGenerator.generate(auditRecord);

            if (!calculatedHash.equals(auditRecord.hash())) {
                return invalid(
                        checkedRecords,
                        auditRecord.seq()
                );
            }

            expectedPreviousHash = auditRecord.hash();
        }

        return new AuditVerification(
                true,
                checkedRecords,
                null,
                clock.instant()
        );
    }

    private String resolveExpectedPreviousHash(
            AuditRecord firstRecord
    ) {
        Optional<AuditRecord> previousRecord =
                auditRecordRepository.findPrevious(firstRecord.seq());

        if (previousRecord.isPresent()) {
            return previousRecord.get().hash();
        }

        return GENESIS_HASH;
    }

    private AuditVerification invalid(
            long checkedRecords,
            long invalidSequence
    ) {
        return new AuditVerification(
                false,
                checkedRecords,
                invalidSequence,
                clock.instant()
        );
    }

    private void validateRange(
            Long fromSequence,
            Long toSequence
    ) {
        if (fromSequence != null && fromSequence < 1) {
            throw new IllegalArgumentException(
                    "fromSeq must be greater than zero"
            );
        }

        if (toSequence != null && toSequence < 1) {
            throw new IllegalArgumentException(
                    "toSeq must be greater than zero"
            );
        }

        if (fromSequence != null
                && toSequence != null
                && fromSequence > toSequence) {
            throw new IllegalArgumentException(
                    "fromSeq must not be greater than toSeq"
            );
        }
    }
}
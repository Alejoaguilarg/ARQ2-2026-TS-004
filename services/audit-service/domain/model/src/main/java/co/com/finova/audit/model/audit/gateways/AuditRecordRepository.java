package co.com.finova.audit.model.audit.gateways;

import co.com.finova.audit.model.audit.AuditPage;
import co.com.finova.audit.model.audit.AuditRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditRecordRepository {

    long nextSequence();

    Optional<AuditRecord> findLast();

    Optional<AuditRecord> findPrevious(long seq);

    void save(AuditRecord auditRecord);

    AuditPage search(
            UUID transferId,
            UUID userId,
            Instant from,
            Instant to,
            int page,
            int size
    );

    List<AuditRecord> findBySequenceRange(
            Long fromSeq,
            Long toSeq
    );
}
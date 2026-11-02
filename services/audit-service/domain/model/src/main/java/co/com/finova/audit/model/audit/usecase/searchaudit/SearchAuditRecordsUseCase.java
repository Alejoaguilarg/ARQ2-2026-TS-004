package co.com.finova.audit.usecase.searchaudit;

import co.com.finova.audit.model.audit.AuditPage;
import co.com.finova.audit.model.audit.gateways.AuditRecordRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class SearchAuditRecordsUseCase {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    private final AuditRecordRepository auditRecordRepository;

    public SearchAuditRecordsUseCase(
            AuditRecordRepository auditRecordRepository
    ) {
        this.auditRecordRepository = Objects.requireNonNull(
                auditRecordRepository,
                "auditRecordRepository is required"
        );
    }

    public AuditPage execute(
            UUID transferId,
            UUID userId,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        validateFilters(transferId, userId, from);
        validatePeriod(from, to);
        validatePagination(page, size);

        return auditRecordRepository.search(
                transferId,
                userId,
                from,
                to,
                page,
                size
        );
    }

    private void validateFilters(
            UUID transferId,
            UUID userId,
            Instant from
    ) {
        if (transferId == null
                && userId == null
                && from == null) {
            throw new IllegalArgumentException(
                    "At least one of transferId, userId or from is required"
            );
        }
    }

    private void validatePeriod(
            Instant from,
            Instant to
    ) {
        if (from != null
                && to != null
                && from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "from must not be after to"
            );
        }

        if (to != null && from == null) {
            throw new IllegalArgumentException(
                    "from is required when to is provided"
            );
        }
    }

    private void validatePagination(
            int page,
            int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "page must be greater than or equal to zero"
            );
        }

        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + MAX_SIZE
            );
        }
    }
}
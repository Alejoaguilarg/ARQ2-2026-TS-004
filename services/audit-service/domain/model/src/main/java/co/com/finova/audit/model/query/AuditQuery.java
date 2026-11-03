package co.com.finova.audit.model.query;

import java.time.Instant;
import java.util.UUID;

/**
 * Filters of GET /audit/records (spec §3.10). At least one of transferId, userId and from is required,
 * so an auditor never pages through the whole trail by accident. from and to bound occurredAt (when
 * the attempt happened), both inclusive. page starts at 0; size is 1..200 and defaults to 50.
 */
public record AuditQuery(UUID transferId, UUID userId, Instant from, Instant to, int page, int size) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public AuditQuery {
        if (transferId == null && userId == null && from == null) {
            throw new InvalidAuditQueryException("transferId", "or userId or from is required");
        }
        if (page < 0) {
            throw new InvalidAuditQueryException("page", "must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidAuditQueryException("size", "must be between 1 and " + MAX_SIZE);
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidAuditQueryException("to", "must not be before from");
        }
    }

    /** A query with the defaults of spec §3.10 for page and size when they are not given. */
    public static AuditQuery of(UUID transferId, UUID userId, Instant from, Instant to, Integer page,
                                Integer size) {
        return new AuditQuery(transferId, userId, from, to,
                page == null ? DEFAULT_PAGE : page,
                size == null ? DEFAULT_SIZE : size);
    }

    /** Number of records before this page. */
    public long offset() {
        return (long) page * size;
    }
}

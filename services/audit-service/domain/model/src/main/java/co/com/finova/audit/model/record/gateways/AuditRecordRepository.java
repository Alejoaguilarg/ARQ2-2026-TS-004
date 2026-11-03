package co.com.finova.audit.model.record.gateways;

import co.com.finova.audit.model.query.AuditPage;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The append-only audit trail in audit_db (spec §2 AuditRecord and AuditEventId). There is no update. */
public interface AuditRecordRepository {

    /**
     * Takes the append lock until the current transaction ends. The queue already has a single active
     * consumer (spec §4.5); the lock keeps the chain linear even if a second writer ever appeared.
     */
    void lockChain();

    /** True when a record with this eventId was already stored (audit_event_ids). */
    boolean contains(UUID eventId);

    /** The last record of the chain, or empty when the trail has no records yet. */
    Optional<ChainLink> findLastLink();

    /** The stored seq and hash of one record, or empty when no record has that seq. */
    Optional<ChainLink> findLink(long seq);

    /** Inserts the record and its audit_event_ids row; must run inside a transaction. */
    void append(AuditRecord record);

    /** One page of the records that match the query, ordered by seq ascending (spec §3.10). */
    AuditPage find(AuditQuery query);

    /** The records with fromSeq &lt;= seq &lt;= toSeq ordered by seq, at most {@code limit} of them. */
    List<AuditRecord> findRange(long fromSeq, long toSeq, int limit);
}

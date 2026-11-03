package co.com.finova.audit.jpa.record;

import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import co.com.finova.audit.model.query.AuditPage;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;
import co.com.finova.audit.model.record.gateways.AuditRecordRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * audit_records and audit_event_ids (V1). Insert and select only: the service logs in as audit_app (V2), which inherits audit_rw,
 * which has no UPDATE, DELETE or TRUNCATE privilege, and a trigger rejects them for every role.
 */
@Repository
public class JdbcAuditRecordRepository implements AuditRecordRepository {

    /** Key of the transaction-level advisory lock that serializes appends ("AUDITCHN" in ASCII). */
    static final long CHAIN_LOCK_KEY = 0x4155444954434843L;

    private static final String COLUMNS = """
            seq, event_id, source, stage, outcome, reason, transfer_id, user_id, idempotency_key, amount,
            http_status, correlation_id, occurred_at, recorded_at, prev_hash, hash""";

    private static final RowMapper<ChainLink> LINK = (rs, rowNum) ->
            new ChainLink(rs.getLong("seq"), rs.getString("hash"));

    private static final RowMapper<AuditRecord> RECORD = JdbcAuditRecordRepository::toRecord;

    private final JdbcClient jdbc;

    public JdbcAuditRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockChain() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", CHAIN_LOCK_KEY)
                .query()
                .listOfRows();
    }

    @Override
    public boolean contains(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM audit_event_ids WHERE event_id = :eventId)")
                .param("eventId", eventId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public Optional<ChainLink> findLastLink() {
        return jdbc.sql("SELECT seq, hash FROM audit_records ORDER BY seq DESC LIMIT 1")
                .query(LINK)
                .optional();
    }

    @Override
    public Optional<ChainLink> findLink(long seq) {
        return jdbc.sql("SELECT seq, hash FROM audit_records WHERE seq = :seq")
                .param("seq", seq)
                .query(LINK)
                .optional();
    }

    @Override
    public void append(AuditRecord record) {
        AttemptRecorded attempt = record.attempt();
        jdbc.sql("INSERT INTO audit_event_ids (event_id, seq) VALUES (:eventId, :seq)")
                .param("eventId", attempt.eventId())
                .param("seq", record.seq())
                .update();
        jdbc.sql("INSERT INTO audit_records (" + COLUMNS + """
                        ) VALUES (:seq, :eventId, :source, :stage, :outcome, :reason, :transferId, :userId,
                                  :idempotencyKey, :amount, :statusCode, :correlationId, :occurredAt, :recordedAt,
                                  :prevHash, :hash)
                        """)
                .param("seq", record.seq())
                .param("eventId", attempt.eventId())
                .param("source", attempt.source().name())
                .param("stage", attempt.stage().name())
                .param("outcome", attempt.outcome().name())
                .param("reason", attempt.reason())
                .param("transferId", attempt.transferId())
                .param("userId", attempt.userId())
                .param("idempotencyKey", attempt.idempotencyKey())
                .param("amount", attempt.amount() == null ? null : new BigDecimal(attempt.amount()))
                .param("statusCode", attempt.statusCode())
                .param("correlationId", attempt.correlationId())
                .param("occurredAt", toDb(attempt.occurredAt()))
                .param("recordedAt", toDb(record.recordedAt()))
                .param("prevHash", record.prevHash())
                .param("hash", record.hash())
                .update();
    }

    @Override
    public AuditPage find(AuditQuery query) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        Map<String, Object> params = new HashMap<>();
        if (query.transferId() != null) {
            where.append(" AND transfer_id = :transferId");
            params.put("transferId", query.transferId());
        }
        if (query.userId() != null) {
            where.append(" AND user_id = :userId");
            params.put("userId", query.userId());
        }
        if (query.from() != null) {
            where.append(" AND occurred_at >= :from");
            params.put("from", toDb(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND occurred_at <= :to");
            params.put("to", toDb(query.to()));
        }

        Long total = jdbc.sql("SELECT count(*) FROM audit_records" + where)
                .params(params)
                .query(Long.class)
                .single();

        params.put("limit", query.size());
        params.put("offset", query.offset());
        List<AuditRecord> items = jdbc.sql("SELECT " + COLUMNS + " FROM audit_records" + where
                        + " ORDER BY seq LIMIT :limit OFFSET :offset")
                .params(params)
                .query(RECORD)
                .list();
        return new AuditPage(query.page(), query.size(), total == null ? 0 : total, items);
    }

    @Override
    public List<AuditRecord> findRange(long fromSeq, long toSeq, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_records"
                        + " WHERE seq BETWEEN :fromSeq AND :toSeq ORDER BY seq LIMIT :limit")
                .param("fromSeq", fromSeq)
                .param("toSeq", toSeq)
                .param("limit", limit)
                .query(RECORD)
                .list();
    }

    private static AuditRecord toRecord(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        AttemptRecorded attempt = new AttemptRecorded(
                rs.getObject("event_id", UUID.class),
                AttemptSource.valueOf(rs.getString("source")),
                AttemptStage.valueOf(rs.getString("stage")),
                AttemptOutcome.valueOf(rs.getString("outcome")),
                rs.getString("reason"),
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("idempotency_key"),
                amount == null ? null : amount.toPlainString(),
                rs.getObject("http_status", Integer.class),
                rs.getObject("correlation_id", UUID.class),
                fromDb(rs, "occurred_at"));
        return new AuditRecord(rs.getLong("seq"), attempt, fromDb(rs, "recorded_at"),
                rs.getString("prev_hash"), rs.getString("hash"));
    }

    private static OffsetDateTime toDb(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}

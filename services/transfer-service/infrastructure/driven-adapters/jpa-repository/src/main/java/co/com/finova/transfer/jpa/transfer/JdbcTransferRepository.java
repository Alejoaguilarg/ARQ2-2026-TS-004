package co.com.finova.transfer.jpa.transfer;

import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.transfer.DuplicateIdempotencyKeyException;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransferRepository implements TransferRepository {

    private static final String COLUMNS = """
            transfer_id, sender_user_id, recipient_user_id, amount, currency, description, idempotency_key,
            request_hash, status, failure_reason, credit_attempts, correlation_id, last_command_id,
            last_command_at, created_at, updated_at, completed_at""";

    private final JdbcClient jdbc;

    public JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Transfer transfer) {
        try {
            jdbc.sql("INSERT INTO transfers (" + COLUMNS + """
                            ) VALUES (:transferId, :senderUserId, :recipientUserId, :amount, :currency, :description,
                                      :idempotencyKey, :requestHash, :status, :failureReason, :creditAttempts,
                                      :correlationId, :lastCommandId, :lastCommandAt, :createdAt, :updatedAt,
                                      :completedAt)
                            """)
                    .paramSource(params(transfer))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException(e);
        }
    }

    /** Only what the saga changes; the request fields never change after the insert. */
    @Override
    public void update(Transfer transfer) {
        int updated = jdbc.sql("""
                        UPDATE transfers
                        SET status = :status, failure_reason = :failureReason, credit_attempts = :creditAttempts,
                            last_command_id = :lastCommandId, last_command_at = :lastCommandAt,
                            updated_at = :updatedAt, completed_at = :completedAt
                        WHERE transfer_id = :transferId
                        """)
                .paramSource(params(transfer))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("transfer " + transfer.transferId() + " not found");
        }
    }

    @Override
    public Optional<Transfer> findById(UUID transferId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE transfer_id = :transferId")
                .param("transferId", transferId)
                .query((rs, rowNum) -> toTransfer(rs))
                .optional();
    }

    @Override
    public Optional<Transfer> lockById(UUID transferId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE transfer_id = :transferId FOR UPDATE")
                .param("transferId", transferId)
                .query((rs, rowNum) -> toTransfer(rs))
                .optional();
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(UUID senderUserId, UUID idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM transfers WHERE sender_user_id = :sender AND idempotency_key = :key")
                .param("sender", senderUserId)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> toTransfer(rs))
                .optional();
    }

    /** Uses the partial index ix_transfers_running. */
    @Override
    public List<UUID> findDue(Instant pendingBefore, Instant debitedBefore, Instant compensatingBefore, int limit) {
        return jdbc.sql("""
                        SELECT transfer_id FROM transfers
                        WHERE (status = 'PENDING' AND last_command_at <= :pendingBefore)
                           OR (status = 'DEBITED' AND last_command_at <= :debitedBefore)
                           OR (status = 'COMPENSATING' AND last_command_at <= :compensatingBefore)
                        ORDER BY last_command_at
                        LIMIT :limit
                        """)
                .param("pendingBefore", utc(pendingBefore))
                .param("debitedBefore", utc(debitedBefore))
                .param("compensatingBefore", utc(compensatingBefore))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private static MapSqlParameterSource params(Transfer t) {
        return new MapSqlParameterSource()
                .addValue("transferId", t.transferId())
                .addValue("senderUserId", t.senderUserId())
                .addValue("recipientUserId", t.recipientUserId())
                .addValue("amount", t.amount().toBigDecimal())
                .addValue("currency", t.currency().name())
                .addValue("description", t.description())
                .addValue("idempotencyKey", t.idempotencyKey())
                .addValue("requestHash", t.requestHash())
                .addValue("status", t.status().name())
                .addValue("failureReason", t.failureReason() == null ? null : t.failureReason().name())
                .addValue("creditAttempts", t.creditAttempts())
                .addValue("correlationId", t.correlationId())
                .addValue("lastCommandId", t.lastCommandId())
                .addValue("lastCommandAt", utc(t.lastCommandAt()))
                .addValue("createdAt", utc(t.createdAt()))
                .addValue("updatedAt", utc(t.updatedAt()))
                .addValue("completedAt", utc(t.completedAt()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Transfer toTransfer(ResultSet rs) throws SQLException {
        String failureReason = rs.getString("failure_reason");
        return new Transfer(
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("sender_user_id", UUID.class),
                rs.getObject("recipient_user_id", UUID.class),
                Money.of(rs.getBigDecimal("amount")),
                Currency.fromCode(rs.getString("currency")),
                rs.getString("description"),
                rs.getObject("idempotency_key", UUID.class),
                rs.getString("request_hash"),
                TransferStatus.valueOf(rs.getString("status")),
                failureReason == null ? null : FailureReason.valueOf(failureReason),
                rs.getInt("credit_attempts"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("last_command_id", UUID.class),
                instant(rs, "last_command_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "completed_at"));
    }
}

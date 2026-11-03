package co.com.finova.wallet.jpa.ledger;

import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.LedgerEntryType;
import co.com.finova.wallet.model.ledger.gateways.LedgerRepository;
import co.com.finova.wallet.model.money.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Insert-only: a trigger rejects UPDATE, DELETE and TRUNCATE on ledger_entries (V1). */
@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private static final String ONE_ENTRY_PER_TRANSFER_AND_TYPE = "uq_ledger_transfer_type";

    private final JdbcClient jdbc;

    public JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(LedgerEntry entry) {
        try {
            jdbc.sql("""
                            INSERT INTO ledger_entries
                                (entry_id, wallet_id, transfer_id, type, amount, balance_after, created_at)
                            VALUES (:entryId, :walletId, :transferId, :type, :amount, :balanceAfter, :createdAt)
                            """)
                    .param("entryId", entry.entryId())
                    .param("walletId", entry.walletId())
                    .param("transferId", entry.transferId())
                    .param("type", entry.type().name())
                    .param("amount", entry.amount().toBigDecimal())
                    .param("balanceAfter", entry.balanceAfter().toBigDecimal())
                    .param("createdAt", OffsetDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains(ONE_ENTRY_PER_TRANSFER_AND_TYPE)) {
                throw new DuplicateLedgerEntryException(entry.transferId(), entry.type());
            }
            throw e;
        }
    }

    @Override
    public Optional<LedgerEntry> findDebit(UUID transferId) {
        return jdbc.sql("""
                        SELECT entry_id, wallet_id, transfer_id, type, amount, balance_after, created_at
                        FROM ledger_entries
                        WHERE transfer_id = :transferId AND type = 'DEBIT'
                        """)
                .param("transferId", transferId)
                .query((rs, rowNum) -> new LedgerEntry(
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("wallet_id", UUID.class),
                        rs.getObject("transfer_id", UUID.class),
                        LedgerEntryType.valueOf(rs.getString("type")),
                        Money.of(rs.getBigDecimal("amount")),
                        Money.of(rs.getBigDecimal("balance_after")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}

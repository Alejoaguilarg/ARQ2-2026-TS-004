package co.com.finova.wallet.jpa.settlement;

import co.com.finova.wallet.model.settlement.SettlementOutcome;
import co.com.finova.wallet.model.settlement.TransferSettlement;
import co.com.finova.wallet.model.settlement.gateways.TransferSettlementRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransferSettlementRepository implements TransferSettlementRepository {

    private final JdbcClient jdbc;

    public JdbcTransferSettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Spec §4.7: the first transaction that inserts the row wins. If another transaction inserted it and has
     * not committed yet, PostgreSQL makes this INSERT wait; after that commit the conflict is detected, and
     * the winner's row is visible to the SELECT that follows (READ COMMITTED).
     */
    @Override
    public Optional<TransferSettlement> claim(TransferSettlement settlement) {
        int inserted = jdbc.sql("""
                        INSERT INTO transfer_settlements (transfer_id, outcome, settled_at)
                        VALUES (:transferId, :outcome, :settledAt)
                        ON CONFLICT (transfer_id) DO NOTHING
                        """)
                .param("transferId", settlement.transferId())
                .param("outcome", settlement.outcome().name())
                .param("settledAt", OffsetDateTime.ofInstant(settlement.settledAt(), ZoneOffset.UTC))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("""
                        SELECT transfer_id, outcome, settled_at FROM transfer_settlements
                        WHERE transfer_id = :transferId
                        """)
                .param("transferId", settlement.transferId())
                .query((rs, rowNum) -> new TransferSettlement(
                        rs.getObject("transfer_id", UUID.class),
                        SettlementOutcome.valueOf(rs.getString("outcome")),
                        rs.getObject("settled_at", OffsetDateTime.class).toInstant()))
                .single());
    }
}

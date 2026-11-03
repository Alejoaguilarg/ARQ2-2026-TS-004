package co.com.finova.wallet.jpa.settlement;

import co.com.finova.wallet.jpa.support.PostgresTestBase;
import co.com.finova.wallet.jpa.transaction.SpringTransactionRunner;
import co.com.finova.wallet.model.settlement.SettlementOutcome;
import co.com.finova.wallet.model.settlement.TransferSettlement;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTransferSettlementRepositoryTest extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private final JdbcTransferSettlementRepository repository =
            new JdbcTransferSettlementRepository(JdbcClient.create(jdbc));

    private final UUID transferId = UUID.randomUUID();

    @Test
    void firstClaimWinsAndIsStored() {
        assertThat(repository.claim(TransferSettlement.credited(transferId, NOW))).isEmpty();

        assertThat(jdbc.queryForObject("SELECT outcome FROM transfer_settlements WHERE transfer_id = ?",
                String.class, transferId)).isEqualTo("CREDITED");
        assertThat(jdbc.queryForObject("SELECT settled_at FROM transfer_settlements WHERE transfer_id = ?",
                OffsetDateTime.class, transferId).toInstant()).isEqualTo(NOW);
    }

    @Test
    void laterClaimGetsTheWinnerAndChangesNothing() {
        repository.claim(TransferSettlement.reversed(transferId, NOW));

        Optional<TransferSettlement> winner = repository.claim(
                TransferSettlement.credited(transferId, NOW.plusSeconds(5)));

        assertThat(winner).contains(new TransferSettlement(transferId, SettlementOutcome.REVERSED, NOW));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_settlements", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameOutcomeClaimedTwiceAlsoReturnsTheExistingRow() {
        repository.claim(TransferSettlement.credited(transferId, NOW));

        assertThat(repository.claim(TransferSettlement.credited(transferId, NOW.plusSeconds(1))))
                .contains(new TransferSettlement(transferId, SettlementOutcome.CREDITED, NOW));
    }

    @Test
    void concurrentClaimsInSeparateTransactionsHaveExactlyOneWinner() throws Exception {
        SpringTransactionRunner transactions =
                new SpringTransactionRunner(new DataSourceTransactionManager(jdbc.getDataSource()));
        int claims = 8;
        ExecutorService pool = Executors.newFixedThreadPool(claims);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<TransferSettlement>>> results = new ArrayList<>();
            for (int i = 0; i < claims; i++) {
                TransferSettlement claim = i % 2 == 0
                        ? TransferSettlement.credited(transferId, NOW)
                        : TransferSettlement.reversed(transferId, NOW);
                results.add(pool.submit(() -> {
                    start.await();
                    return transactions.inTransaction(() -> repository.claim(claim));
                }));
            }
            start.countDown();

            List<Optional<TransferSettlement>> outcomes = new ArrayList<>();
            for (Future<Optional<TransferSettlement>> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(Optional::isEmpty).hasSize(1);
            String stored = jdbc.queryForObject("SELECT outcome FROM transfer_settlements WHERE transfer_id = ?",
                    String.class, transferId);
            assertThat(outcomes).filteredOn(Optional::isPresent)
                    .allSatisfy(loser -> assertThat(loser.get().outcome().name()).isEqualTo(stored));
        } finally {
            pool.shutdownNow();
        }
    }
}

package co.com.finova.wallet.jpa.transaction;

import co.com.finova.wallet.jpa.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringTransactionRunnerTest extends PostgresTestBase {

    private final SpringTransactionRunner runner =
            new SpringTransactionRunner(new DataSourceTransactionManager(jdbc.getDataSource()));

    @Test
    void commitsAndReturnsTheResult() {
        UUID walletId = UUID.randomUUID();

        String result = runner.inTransaction(() -> {
            insertWallet(walletId);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(countWallets()).isEqualTo(1);
    }

    @Test
    void exceptionRollsBackEverything() {
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> runner.inTransaction(() -> {
            insertWallet(UUID.randomUUID());
            insertWallet(UUID.randomUUID());
            throw failure;
        })).isSameAs(failure);

        assertThat(countWallets()).isZero();
    }

    private void insertWallet(UUID walletId) {
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, 10.00, 'COP', 0, now())
                """, walletId, UUID.randomUUID());
    }

    private Integer countWallets() {
        return jdbc.queryForObject("SELECT count(*) FROM wallets", Integer.class);
    }
}

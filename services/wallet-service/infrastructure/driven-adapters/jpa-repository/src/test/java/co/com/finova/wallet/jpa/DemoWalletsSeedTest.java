package co.com.finova.wallet.jpa;

import co.com.finova.wallet.jpa.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** V2 seeds one wallet per identity demo user, each balance backed by its OPENING entry (spec §1.6). */
class DemoWalletsSeedTest extends PostgresTestBase {

    @BeforeEach
    void applySeedAgain() throws Exception {
        // The base truncates every table before each test, so the seed runs again on the empty schema.
        jdbc.execute(new ClassPathResource("db/migration/V2__demo_wallets.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void givesEachDemoUserItsWallet() {
        assertThat(balance("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c")).isEqualByComparingTo("500000.00");
        assertThat(balance("2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f")).isEqualByComparingTo("100000.00");
        assertThat(balance("5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c")).isEqualByComparingTo("0.00");
    }

    @Test
    void backsEveryBalanceWithItsLedger() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wallets w
                WHERE w.balance <> (SELECT coalesce(sum(amount), 0) FROM ledger_entries l WHERE l.wallet_id = w.wallet_id)
                """, Integer.class)).isZero();
    }

    private BigDecimal balance(String userId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ?::uuid", BigDecimal.class, userId);
    }
}

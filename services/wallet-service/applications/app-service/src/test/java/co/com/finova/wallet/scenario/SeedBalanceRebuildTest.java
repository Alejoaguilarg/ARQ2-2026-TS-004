package co.com.finova.wallet.scenario;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Spec §1.6 and decision S6: the wallets provisioned by V2__demo_wallets.sql (the Identity demo users) are in
 * wallet_db exactly as Flyway leaves it, each balance equals its ledger, and the rebuild job caches all of them in
 * Redis at startup. The ids are copied from the migration on purpose: if the seed changes, this test says so.
 */
// Own containers and no TRUNCATE, so the database is the migrated one. No RabbitMQ: listeners and relay stay off.
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "adapters.outbox.relay-enabled=false", "adapters.balance-rebuild.enabled=true"})
@Testcontainers
class SeedBalanceRebuildTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** userId → {walletId, opening balance}, as in V2__demo_wallets.sql. */
    private static final Map<String, String[]> SEED = Map.of(
            "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c", new String[] {"a1c4e7f0-3b6d-4a9c-8e2f-5b8d1a4c7e01", "500000.00"},
            "2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f", new String[] {"b2d5f8a1-4c7e-4bad-9f3a-6c9e2b5d8f02", "100000.00"},
            "5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c", new String[] {"c3e6a9b2-5d8f-4cbe-a04b-7daf3c6e9a03", "0.00"});

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void S6_rebuildCachesEverySeedWalletAtStartup() {
        await(() -> SEED.keySet().stream().allMatch(userId -> !cached(userId).isEmpty()));

        SEED.forEach((userId, wallet) -> assertThat(cached(userId))
                .containsEntry("walletId", wallet[0])
                .containsEntry("available", wallet[1])
                .containsEntry("currency", "COP")
                .containsEntry("version", "0"));
        assertThat(redis.keys("balance:*")).hasSize(SEED.size());
    }

    @Test
    void seedBalancesEqualTheirLedgerAfterMigration() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallets", Integer.class)).isEqualTo(SEED.size());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wallets w
                WHERE w.balance <> (SELECT coalesce(sum(CASE WHEN l.type = 'DEBIT' THEN -l.amount ELSE l.amount END), 0)
                                    FROM ledger_entries l WHERE l.wallet_id = w.wallet_id)
                """, Integer.class)).isZero();
        // Pending item 17: a wallet provisioned with 0.00 has no OPENING entry, because ledger amounts must be > 0.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE wallet_id = 'c3e6a9b2-5d8f-4cbe-a04b-7daf3c6e9a03'::uuid",
                Integer.class)).isZero();
    }

    private Map<Object, Object> cached(String userId) {
        return redis.opsForHash().entries("balance:" + userId);
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("timed out after " + TIMEOUT);
    }
}

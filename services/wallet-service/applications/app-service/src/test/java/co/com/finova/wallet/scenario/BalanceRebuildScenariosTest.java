package co.com.finova.wallet.scenario;

import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Spec §4.5 and ADR 004: the rebuild job recomputes the balance cache from wallet_db (decision S6), through the
 * real scheduler, PostgreSQL 16 and Redis 7. The job runs every 500 ms here instead of 5 min.
 */
// No RabbitMQ here: the listeners and the outbox relay stay off.
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "adapters.outbox.relay-enabled=false",
        "adapters.balance-rebuild.enabled=true", "adapters.balance-rebuild.interval=500ms",
        "spring.data.redis.timeout=2s", "spring.data.redis.connect-timeout=2s"})
@Testcontainers
class BalanceRebuildScenariosTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanStores() {
        // The ledger trigger rejects TRUNCATE; replica mode skips triggers for this cleanup only.
        jdbc.execute("""
                SET session_replication_role = replica;
                TRUNCATE outbox_messages, processed_messages, transfer_settlements, ledger_entries, wallets;
                SET session_replication_role = DEFAULT;
                """);
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void F2_4_rebuildNeverOverwritesANewerVersion() {
        UUID user = UUID.randomUUID();
        UUID walletId = givenWallet(user, "500000.00", 8);
        givenCached(user, walletId, "420000.00", 9);
        // Inserted last: once the job repairs it, a run has read every wallet above.
        UUID control = UUID.randomUUID();
        givenCached(control, givenWallet(control, "1.00", 2), "9.00", 1);

        await(() -> "2".equals(cached(control).get("version")));

        assertThat(cached(user)).containsEntry("version", "9").containsEntry("available", "420000.00");
    }

    @Test
    void rebuildRepairsAnEntryLeftStaleByADeadLetteredEvent() {
        UUID user = UUID.randomUUID();
        UUID walletId = givenWallet(user, "350000.00", 5);
        // The BalanceChanged of version 5 ended in wallet.balance-projection.q.dlq.
        givenCached(user, walletId, "500000.00", 4);

        await(() -> "5".equals(cached(user).get("version")));

        assertThat(cached(user)).containsEntry("walletId", walletId.toString())
                .containsEntry("available", "350000.00")
                .containsEntry("currency", "COP");
    }

    @Test
    void rebuildFillsAMissingEntry() {
        UUID user = UUID.randomUUID();
        UUID walletId = givenWallet(user, "100000.00", 0);

        await(() -> !cached(user).isEmpty());

        assertThat(cached(user)).containsEntry("walletId", walletId.toString())
                .containsEntry("available", "100000.00")
                .containsEntry("version", "0");
    }

    private UUID givenWallet(UUID userId, String balance, long version) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, ?, 'COP', ?, now())
                """, walletId, userId, new BigDecimal(balance), version);
        return walletId;
    }

    private void givenCached(UUID userId, UUID walletId, String available, long version) {
        redis.<String, String>opsForHash().putAll("balance:" + userId, Map.of(
                "walletId", walletId.toString(),
                "available", available,
                "currency", "COP",
                "version", Long.toString(version),
                "asOf", "2026-11-02T15:00:00.120Z"));
    }

    private Map<Object, Object> cached(UUID userId) {
        return redis.opsForHash().entries("balance:" + userId);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
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

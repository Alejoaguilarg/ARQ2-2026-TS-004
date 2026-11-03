package co.com.finova.wallet.redis.balance;

import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;
import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The compare-and-set of balance:{userId} (spec §2.1) against a real Redis 7 (same image as the compose). */
class RedisBalanceReadModelTest {

    // Wait for Redis itself, not only the mapped port: the 200 ms command timeout leaves no room for a
    // server that is still starting while the parallel build loads the machine.
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
    private static final LettuceConnectionFactory CONNECTIONS;
    private static final StringRedisTemplate TEMPLATE;

    static {
        REDIS.start();
        CONNECTIONS = connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        TEMPLATE = new StringRedisTemplate(CONNECTIONS);
    }

    private final RedisBalanceReadModel readModel = new RedisBalanceReadModel(TEMPLATE);

    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant asOf = Instant.parse("2026-10-19T15:00:00.120Z");

    @AfterAll
    static void stop() {
        CONNECTIONS.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void emptyRedis() {
        TEMPLATE.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void findIsEmptyWithoutKey() {
        assertThat(readModel.find(userId)).isEmpty();
    }

    @Test
    void storeWritesTheHashWhenTheKeyIsMissing() {
        BalanceSnapshot snapshot = snapshot("850000.00", 42);

        assertThat(readModel.storeIfNewer(snapshot)).isTrue();

        assertThat(TEMPLATE.<String, String>opsForHash().entries("balance:" + userId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "walletId", walletId.toString(),
                "available", "850000.00",
                "currency", "COP",
                "version", "42",
                "asOf", "2026-10-19T15:00:00.120Z"));
        assertThat(TEMPLATE.getExpire("balance:" + userId)).as("no TTL (spec §2.1)").isEqualTo(-1L);
        assertThat(readModel.find(userId)).contains(snapshot);
    }

    @Test
    void findReadsZeroBalanceAndMicrosecondAsOf() {
        BalanceSnapshot snapshot = new BalanceSnapshot(walletId, userId, Money.ZERO, Currency.COP, 0,
                Instant.parse("2026-11-02T15:00:00.123456Z"));
        readModel.storeIfNewer(snapshot);

        assertThat(readModel.find(userId)).contains(snapshot);
    }

    @Test
    void storeReplacesALowerVersion() {
        readModel.storeIfNewer(snapshot("100.00", 8));
        BalanceSnapshot newer = snapshot("90.00", 9);

        assertThat(readModel.storeIfNewer(newer)).isTrue();

        assertThat(readModel.find(userId)).contains(newer);
    }

    @Test
    void F2_3_storeRejectsAnOlderVersion() {
        BalanceSnapshot stored = snapshot("100.00", 9);
        readModel.storeIfNewer(stored);

        assertThat(readModel.storeIfNewer(snapshot("700.00", 7))).isFalse();

        assertThat(readModel.find(userId)).contains(stored);
    }

    @Test
    void F2_4_warmUpWithVersion8AfterProjectionWithVersion9IsRejected() {
        BalanceSnapshot projected = snapshot("350000.00", 9);
        readModel.storeIfNewer(projected);

        assertThat(readModel.storeIfNewer(snapshot("500000.00", 8))).isFalse();

        assertThat(readModel.find(userId)).contains(projected);
    }

    @Test
    void storeRejectsTheSameVersion() {
        BalanceSnapshot stored = snapshot("100.00", 9);
        readModel.storeIfNewer(stored);

        assertThat(readModel.storeIfNewer(snapshot("1.00", 9))).isFalse();

        assertThat(readModel.find(userId)).contains(stored);
    }

    @Test
    void keysOfDifferentUsersAreIndependent() {
        readModel.storeIfNewer(snapshot("100.00", 9));
        UUID otherUser = UUID.randomUUID();
        BalanceSnapshot other = new BalanceSnapshot(UUID.randomUUID(), otherUser, Money.parse("5.00"), Currency.COP,
                1, asOf);

        assertThat(readModel.storeIfNewer(other)).isTrue();

        assertThat(readModel.find(otherUser)).contains(other);
    }

    @Test
    void unreachableRedisIsReportedAsUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LettuceConnectionFactory unreachable = connectionFactory("localhost", closedPort);
        try {
            RedisBalanceReadModel down = new RedisBalanceReadModel(new StringRedisTemplate(unreachable));

            assertThatThrownBy(() -> down.find(userId)).isInstanceOf(BalanceReadModelUnavailableException.class);
            assertThatThrownBy(() -> down.storeIfNewer(snapshot("1.00", 1)))
                    .isInstanceOf(BalanceReadModelUnavailableException.class);
        } finally {
            unreachable.destroy();
        }
    }

    private BalanceSnapshot snapshot(String available, long version) {
        return new BalanceSnapshot(walletId, userId, Money.parse(available), Currency.COP, version, asOf);
    }

    private static LettuceConnectionFactory connectionFactory(String host, int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}

package co.com.finova.wallet.redis;

import co.com.finova.wallet.model.balance.Balance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisBalanceCacheIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisBalanceCache cache;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        cache = new RedisBalanceCache(redis);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void storesUnderTheSpecKeyAndReadsItBack() {
        Balance balance = balance(UUID.randomUUID(), "850000.00", 42);

        assertThat(cache.putIfNewer(balance)).isTrue();

        assertThat(redis.hasKey("balance:" + balance.userId())).isTrue();
        assertThat(cache.find(balance.userId())).contains(balance);
    }

    @Test
    void missingKeyIsEmpty() {
        assertThat(cache.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void f23_olderEventIsDiscarded() {
        UUID user = UUID.randomUUID();
        cache.putIfNewer(balance(user, "900.00", 9));

        assertThat(cache.putIfNewer(balance(user, "700.00", 7))).isFalse();

        assertThat(cache.find(user)).get().extracting(Balance::version).isEqualTo(9L);
    }

    @Test
    void f24_lateWarmUpWithOlderVersionIsRejected() {
        UUID user = UUID.randomUUID();
        cache.putIfNewer(balance(user, "900.00", 9));

        assertThat(cache.putIfNewer(balance(user, "800.00", 8))).isFalse();

        assertThat(cache.find(user)).get().extracting(Balance::available).isEqualTo(new BigDecimal("900.00"));
    }

    @Test
    void sameVersionIsNotRewritten() {
        UUID user = UUID.randomUUID();
        cache.putIfNewer(balance(user, "900.00", 9));

        assertThat(cache.putIfNewer(balance(user, "1.00", 9))).isFalse();
    }

    @Test
    void newerVersionReplacesTheCachedBalance() {
        UUID user = UUID.randomUUID();
        cache.putIfNewer(balance(user, "900.00", 9));

        assertThat(cache.putIfNewer(balance(user, "1000.00", 10))).isTrue();

        assertThat(cache.find(user)).get().extracting(Balance::available).isEqualTo(new BigDecimal("1000.00"));
    }

    private static Balance balance(UUID userId, String available, long version) {
        return new Balance(UUID.randomUUID(), userId, new BigDecimal(available), "COP", version,
                Instant.parse("2026-10-19T15:00:00.120Z"));
    }
}

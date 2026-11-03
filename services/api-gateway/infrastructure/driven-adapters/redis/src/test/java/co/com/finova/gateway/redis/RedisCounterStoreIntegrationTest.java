package co.com.finova.gateway.redis;

import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisCounterStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisCounterStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        store = new RedisCounterStore(redis);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void firstIncrementStartsTheWindowAndLaterOnesDoNotExtendIt() {
        String key = "rl:test:" + UUID.randomUUID();

        assertThat(store.increment(key, Duration.ofSeconds(60))).isEqualTo(1);
        redis.expire(key, Duration.ofSeconds(10));
        assertThat(store.increment(key, Duration.ofSeconds(60))).isEqualTo(2);

        assertThat(store.timeToLive(key)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    @Test
    void windowExpiresAndTheCountRestarts() throws InterruptedException {
        String key = "rl:test:" + UUID.randomUUID();
        store.increment(key, Duration.ofMillis(100));
        store.increment(key, Duration.ofMillis(100));

        Thread.sleep(250);

        assertThat(store.increment(key, Duration.ofMillis(100))).isEqualTo(1);
    }

    @Test
    void setExistsAndDelete() {
        String key = "lock:login:" + UUID.randomUUID();

        store.set(key, Duration.ofMinutes(15));
        assertThat(store.exists(key)).isTrue();
        assertThat(redis.getExpire(key)).isBetween(890L, 900L);

        store.delete(key);
        assertThat(store.exists(key)).isFalse();
    }

    @Test
    void missingKeyHasNoTimeToLive() {
        assertThat(store.timeToLive("rl:test:missing")).isEqualTo(Duration.ZERO);
    }

    @Test
    void unreachableRedisIsReportedAsUnavailable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        dead.afterPropertiesSet();
        try {
            RedisCounterStore unavailable = new RedisCounterStore(new StringRedisTemplate(dead));

            assertThatThrownBy(() -> unavailable.increment("rl:x:y", Duration.ofMinutes(1)))
                    .isInstanceOf(SecurityStoreUnavailableException.class);
        } finally {
            dead.destroy();
        }
    }
}

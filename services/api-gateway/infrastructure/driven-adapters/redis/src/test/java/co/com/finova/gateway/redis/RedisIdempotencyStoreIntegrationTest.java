package co.com.finova.gateway.redis;

import co.com.finova.gateway.model.idempotency.IdempotencyRecord;
import co.com.finova.gateway.model.idempotency.IdempotencyStoreUnavailableException;
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
class RedisIdempotencyStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisIdempotencyStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        store = new RedisIdempotencyStore(redis);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void f32_createsTheSpecKeyInProgressWithA24HourTtl() {
        String key = UUID.randomUUID().toString();

        assertThat(store.createIfAbsent("u1", key, IdempotencyRecord.inProgress("h"))).isTrue();

        assertThat(redis.opsForValue().get("idem:u1:" + key)).isEqualTo("{\"state\":\"IN_PROGRESS\",\"requestHash\":\"h\"}");
        assertThat(redis.getExpire("idem:u1:" + key)).isBetween(86_000L, 86_400L);
    }

    @Test
    void f33_secondCreateWithTheSameKeyFails() {
        String key = UUID.randomUUID().toString();
        store.createIfAbsent("u1", key, IdempotencyRecord.inProgress("h"));

        assertThat(store.createIfAbsent("u1", key, IdempotencyRecord.inProgress("h"))).isFalse();
    }

    @Test
    void keysAreScopedPerUser() {
        String key = UUID.randomUUID().toString();
        store.createIfAbsent("u1", key, IdempotencyRecord.inProgress("h"));

        assertThat(store.createIfAbsent("u2", key, IdempotencyRecord.inProgress("h"))).isTrue();
    }

    @Test
    void completedRecordRoundTripsAndDeleteRemovesIt() {
        String key = UUID.randomUUID().toString();
        IdempotencyRecord completed = IdempotencyRecord.completed("h", 202, "{\"transferId\":\"t\"}");

        store.save("u1", key, completed);
        assertThat(store.find("u1", key)).contains(completed);

        store.delete("u1", key);
        assertThat(store.find("u1", key)).isEmpty();
    }

    @Test
    void f36_unreachableRedisIsReportedAsUnavailable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        dead.afterPropertiesSet();
        try {
            RedisIdempotencyStore unavailable = new RedisIdempotencyStore(new StringRedisTemplate(dead));

            assertThatThrownBy(() -> unavailable.createIfAbsent("u1", "k", IdempotencyRecord.inProgress("h")))
                    .isInstanceOf(IdempotencyStoreUnavailableException.class);
        } finally {
            dead.destroy();
        }
    }
}

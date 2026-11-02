package co.com.finova.identity.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisRevocationStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisRevocationStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        store = new RedisRevocationStore(redis);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void writesTheSpecKeysWithTheirTtl() {
        store.revokeToken("jti-1", Duration.ofSeconds(600));
        store.revokeSession("sid-1", Duration.ofSeconds(900));

        assertThat(redis.opsForValue().get("revoked:jti:jti-1")).isEqualTo("1");
        assertThat(redis.getExpire("revoked:jti:jti-1")).isBetween(590L, 600L);
        assertThat(redis.opsForValue().get("revoked:sid:sid-1")).isEqualTo("1");
        assertThat(redis.getExpire("revoked:sid:sid-1")).isBetween(890L, 900L);
    }
}

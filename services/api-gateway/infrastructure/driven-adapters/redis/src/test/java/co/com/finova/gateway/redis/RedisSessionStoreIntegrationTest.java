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
class RedisSessionStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisSessionStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        store = new RedisSessionStore(redis);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void notRevokedWhenNeitherKeyExists() {
        assertThat(store.isRevoked(id(), id())).isFalse();
    }

    @Test
    void f14_revokedByTokenOrBySession() {
        String jti = id();
        String sid = id();
        // Keys as Identity's RedisRevocationStore writes them.
        redis.opsForValue().set("revoked:jti:" + jti, "1", Duration.ofMinutes(1));
        redis.opsForValue().set("revoked:sid:" + sid, "1", Duration.ofMinutes(1));

        assertThat(store.isRevoked(jti, id())).isTrue();
        assertThat(store.isRevoked(id(), sid)).isTrue();
    }

    @Test
    void firstClaimMarksTheSessionForFiveMinutes() {
        String jti = id();

        assertThat(store.claimFinancialSession(jti, "k1")).isEmpty();

        assertThat(redis.opsForValue().get("fs:used:" + jti)).isEqualTo("k1");
        assertThat(redis.getExpire("fs:used:" + jti)).isBetween(295L, 300L);
    }

    @Test
    void laterClaimsReportTheFirstKeyAndDoNotOverwriteIt() {
        String jti = id();
        store.claimFinancialSession(jti, "k1");

        assertThat(store.claimFinancialSession(jti, "k2")).contains("k1");
        assertThat(redis.opsForValue().get("fs:used:" + jti)).isEqualTo("k1");
    }

    @Test
    void unreachableRedisIsReportedAsUnavailable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        dead.afterPropertiesSet();
        try {
            RedisSessionStore unavailable = new RedisSessionStore(new StringRedisTemplate(dead));

            assertThatThrownBy(() -> unavailable.isRevoked("j", "s")).isInstanceOf(SecurityStoreUnavailableException.class);
        } finally {
            dead.destroy();
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}

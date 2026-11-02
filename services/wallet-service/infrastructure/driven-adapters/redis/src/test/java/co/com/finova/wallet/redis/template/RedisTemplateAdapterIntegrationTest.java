package co.com.finova.wallet.redis.template;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivecommons.utils.ObjectMapperImp;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisTemplateAdapterIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplateAdapter adapter;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        adapter = new RedisTemplateAdapter(connectionFactory, new ObjectMapperImp());
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void savesAndReadsBackAValue() {
        adapter.save("it:value", Map.of("currency", "COP"));

        assertThat(adapter.findById("it:value")).isEqualTo(Map.of("currency", "COP"));
    }

    @Test
    void storesKeysAsPlainTextSoOtherClientsCanReadThem() {
        adapter.save("it:plain", Map.of("k", "v"));

        assertThat(new StringRedisTemplate(connectionFactory).hasKey("it:plain")).isTrue();
    }

    @Test
    void savesWithExpiration() {
        adapter.save("it:ttl", Map.of("k", "v"), 60_000);

        Long ttlSeconds = new StringRedisTemplate(connectionFactory).getExpire("it:ttl");
        assertThat(ttlSeconds).isBetween(1L, 60L);
    }

    @Test
    void returnsNullForMissingKey() {
        assertThat(adapter.findById("it:missing")).isNull();
    }
}

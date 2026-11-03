package co.com.finova.wallet.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2.6: the Gateway's checks passed, but Wallet's own Redis read fails or times out → Wallet reads
 * wallet_db and answers 200. With spring.data.redis.timeout = 200 ms the answer must take under 1 s.
 * Its own Redis container, because the last test stops it.
 */
// No RabbitMQ here: the use cases are called directly, so the listeners and the outbox relay stay off.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "adapters.outbox.relay-enabled=false"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BalanceRedisFailureScenariosTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LIMIT = Duration.ofSeconds(1);

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID userId = UUID.randomUUID();
    private UUID walletId;

    @BeforeEach
    void givenWallet() {
        walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, 420000.00, 'COP', 4, now())
                """, walletId, userId);
        // Warm up the JVM, the pools and the Redis connection with Redis still healthy; Redis keeps version 4.
        assertThat(get().getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE wallets SET balance = 410000.00, version = 5 WHERE wallet_id = ?", walletId);
    }

    @Test
    @Order(1)
    void F2_6_redisNotRespondingFallsBackToPostgres() throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            ResponseEntity<String> response = get();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertAnsweredFromPostgres(response);
            assertThat(elapsed).isLessThan(LIMIT);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    @Order(2)
    void F2_6_redisDownFallsBackToPostgres() throws Exception {
        REDIS.stop();

        long start = System.nanoTime();
        ResponseEntity<String> response = get();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertAnsweredFromPostgres(response);
        assertThat(elapsed).isLessThan(LIMIT);
    }

    /** Redis still holds version 4 (from the warm-up); wallet_db holds version 5. */
    private void assertAnsweredFromPostgres(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("walletId").asText()).isEqualTo(walletId.toString());
        assertThat(body.get("available").asText()).isEqualTo("410000.00");
        assertThat(body.get("version").asLong()).isEqualTo(5);
    }

    private ResponseEntity<String> get() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        return http.exchange("/wallets/me/balance", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}

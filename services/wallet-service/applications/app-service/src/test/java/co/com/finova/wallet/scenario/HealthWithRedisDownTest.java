package co.com.finova.wallet.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision S7 (pending item 24; the spec does not define the health contents): Redis is left out of
 * /actuator/health. With Redis down the Wallet still answers the balance from wallet_db (F2.6) and never needs
 * Redis to move money, so the compose healthcheck must keep it healthy. Its own Redis container, because the tests
 * pause it.
 */
// No RabbitMQ here: the listeners and the outbox relay stay off, and the RabbitMQ health check is turned off in this
// test only, so the status reflects PostgreSQL and the missing Redis check.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "adapters.outbox.relay-enabled=false",
                "management.health.rabbit.enabled=false"})
@Testcontainers
class HealthWithRedisDownTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final ObjectMapper JSON = new ObjectMapper();

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
                VALUES (?, ?, 250000.00, 'COP', 3, now())
                """, walletId, userId);
    }

    @Test
    void S7_healthStaysUpWhenRedisIsDown() throws Exception {
        whileRedisIsPaused(() -> {
            ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(JSON.readTree(response.getBody()).get("status").asText()).isEqualTo("UP");
        });
    }

    @Test
    void S7_balanceStillAnswersWhileHealthIsUp() throws Exception {
        whileRedisIsPaused(() -> {
            ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
            ResponseEntity<String> balance = getBalance();

            assertThat(health.getStatusCode().value()).isEqualTo(200);
            assertThat(balance.getStatusCode().value()).isEqualTo(200);
            JsonNode body = JSON.readTree(balance.getBody());
            assertThat(body.get("walletId").asText()).isEqualTo(walletId.toString());
            assertThat(body.get("available").asText()).isEqualTo("250000.00");
            assertThat(body.get("version").asLong()).isEqualTo(3);
        });
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void whileRedisIsPaused(Check check) throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            check.run();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private ResponseEntity<String> getBalance() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        return http.exchange("/wallets/me/balance", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}

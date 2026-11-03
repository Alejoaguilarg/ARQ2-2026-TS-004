package co.com.finova.wallet.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * PostgreSQL 16, Redis 7 and a local RabbitMQ 4.1 with the team topology (infra/rabbitmq/definitions.json),
 * loaded the same way the compose rabbitmq-init container does. Never the shared RabbitMQ account.
 * The containers start once per JVM and are shared by every subclass. Each test class closes its Spring
 * context when it ends, so listeners of a cached context never compete for the messages of the next class.
 * Redis gets 2 s instead of the production 200 ms (F2.6 is tested in BalanceRedisFailureScenariosTest):
 * these tests do not measure Redis latency, and a loaded Docker Desktop can exceed 200 ms.
 */
@DirtiesContext
@TestPropertySource(properties = {"spring.data.redis.timeout=2s", "spring.data.redis.connect-timeout=2s"})
public abstract class MessagingTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    /** Every queue the Wallet consumes or publishes to, with its .retry and .dlq. */
    static final List<String> QUEUES = List.of(
            "wallet.commands.q", "wallet.commands.q.retry", "wallet.commands.q.dlq",
            "wallet.balance-projection.q", "wallet.balance-projection.q.retry", "wallet.balance-projection.q.dlq",
            "transfer.wallet-events.q", "transfer.wallet-events.q.retry", "transfer.wallet-events.q.dlq",
            "audit.attempts.q", "audit.attempts.q.retry", "audit.attempts.q.dlq");

    static {
        POSTGRES.start();
        REDIS.start();
        RABBIT.start();
        loadDefinitions();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected RabbitTemplate rabbit;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        // The ledger trigger rejects TRUNCATE; replica mode skips triggers for this cleanup only.
        jdbc.execute("""
                SET session_replication_role = replica;
                TRUNCATE outbox_messages, processed_messages, transfer_settlements, ledger_entries, wallets;
                SET session_replication_role = DEFAULT;
                """);
        rabbit.execute(channel -> {
            for (String queue : QUEUES) {
                channel.queuePurge(queue);
            }
            return null;
        });
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ---------------------------------------------------------------- publishing like Transfer does

    /** Publishes a message the way Transfer's relay does: AMQP message-id = envelope messageId (spec §4.1). */
    protected void publish(String exchange, String routingKey, String messageId, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(exchange, routingKey, new Message(json.getBytes(StandardCharsets.UTF_8), properties));
    }

    protected static String envelope(String messageId, String type, UUID correlationId, UUID transferId,
                                     String producer, String payloadJson) {
        return """
                {"messageId":"%s","type":"%s","correlationId":"%s","transferId":%s,"producer":"%s",
                 "occurredAt":"%s","payload":%s}"""
                .formatted(messageId, type, correlationId, transferId == null ? "null" : "\"" + transferId + "\"",
                        producer, Instant.now(), payloadJson);
    }

    protected static String debitWallet(UUID transferId, UUID userId, String amount) {
        return """
                {"transferId":"%s","userId":"%s","amount":"%s","currency":"COP"}"""
                .formatted(transferId, userId, amount);
    }

    protected static String creditWallet(UUID transferId, UUID userId, String amount, int attempt) {
        return """
                {"transferId":"%s","userId":"%s","amount":"%s","currency":"COP","attempt":%d}"""
                .formatted(transferId, userId, amount, attempt);
    }

    protected static String reverseDebit(UUID transferId, UUID userId, String amount, String reason) {
        return """
                {"transferId":"%s","userId":"%s","amount":"%s","currency":"COP","reason":"%s"}"""
                .formatted(transferId, userId, amount, reason);
    }

    // ---------------------------------------------------------------- state helpers

    protected UUID givenWallet(UUID userId, String balance) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, ?, 'COP', 0, now())
                """, walletId, userId, new BigDecimal(balance));
        return walletId;
    }

    protected Message receive(String queue, Duration timeout) {
        return rabbit.receive(queue, timeout.toMillis());
    }

    protected long messageCount(String queue) {
        return rabbit.execute(channel -> (long) channel.queueDeclarePassive(queue).getMessageCount());
    }

    protected static void await(String description, Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + description);
            }
        }
        fail("timed out after " + timeout + " waiting for " + description);
    }

    protected static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    // ---------------------------------------------------------------- topology

    /** Queue names that exist in the broker, read from the management API. */
    protected static List<String> brokerQueues() throws IOException, InterruptedException {
        HttpRequest request = managementRequest("/api/queues/%2F").GET().build();
        HttpResponse<String> response = managementClient().send(request, HttpResponse.BodyHandlers.ofString());
        List<String> names = new ArrayList<>();
        new ObjectMapper().readTree(response.body()).forEach(queue -> names.add(queue.get("name").asText()));
        return names;
    }

    /** Queue names defined in infra/rabbitmq/definitions.json. */
    protected static List<String> definitionQueues() throws IOException {
        List<String> names = new ArrayList<>();
        new ObjectMapper().readTree(definitionsFile().toFile()).get("queues")
                .forEach(queue -> names.add(queue.get("name").asText()));
        return names;
    }

    private static HttpRequest.Builder managementRequest(String path) {
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        return HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }

    // HTTP/1.1: the management API closes the connection on an h2c upgrade with a request body.
    private static HttpClient managementClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    /** The management API starts a moment after AMQP, so the POST is retried for up to 30 s. */
    private static void loadDefinitions() {
        String definitions;
        try {
            definitions = Files.readString(definitionsFile());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read infra/rabbitmq/definitions.json", e);
        }
        HttpRequest request = managementRequest("/api/definitions")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(definitions))
                .build();
        Instant deadline = Instant.now().plusSeconds(30);
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = managementClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return;
                }
                last = new IllegalStateException(response.statusCode() + " " + response.body());
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("cannot load infra/rabbitmq/definitions.json", last);
    }

    /** infra/rabbitmq/definitions.json at the repository root, found from the module directory. */
    private static Path definitionsFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("infra/rabbitmq/definitions.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("infra/rabbitmq/definitions.json not found");
    }
}

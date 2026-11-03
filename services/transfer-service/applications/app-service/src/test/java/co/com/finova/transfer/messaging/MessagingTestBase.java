package co.com.finova.transfer.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.io.IOException;
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
 * PostgreSQL 16 and a local RabbitMQ 4.1 with the team topology (infra/rabbitmq/definitions.json), loaded the
 * same way the compose rabbitmq-init container does. Never a shared broker. The containers start once per JVM
 * and are shared by every subclass. Each test class closes its Spring context when it ends, so listeners of a
 * cached context never compete for the messages of the next class. Wallet is played by the test: it reads the
 * commands from wallet.commands.q and publishes the results to wallet.events.
 */
@DirtiesContext
public abstract class MessagingTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_db");


    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    /** Every queue Transfer consumes or publishes to, with its .retry and .dlq. */
    static final List<String> QUEUES = List.of(
            "wallet.commands.q", "wallet.commands.q.retry", "wallet.commands.q.dlq",
            "transfer.wallet-events.q", "transfer.wallet-events.q.retry", "transfer.wallet-events.q.dlq",
            "notification.transfer-events.q", "notification.transfer-events.q.retry",
            "notification.transfer-events.q.dlq",
            "audit.attempts.q", "audit.attempts.q.retry", "audit.attempts.q.dlq");

    static {
        POSTGRES.start();
        RABBIT.start();
        loadDefinitions();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected RabbitTemplate rabbit;

    @BeforeEach
    void cleanState() {
        jdbc.execute("TRUNCATE outbox_messages, processed_messages, transfers");
        rabbit.execute(channel -> {
            for (String queue : QUEUES) {
                channel.queuePurge(queue);
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- playing Wallet

    /** Publishes a Wallet result the way Wallet's relay does: AMQP message-id = envelope messageId (spec §4.1). */
    protected void publishWalletResult(String routingKey, String type, UUID correlationId, UUID transferId,
                                       String payloadJson) {
        String messageId = UUID.randomUUID().toString();
        String json = """
                {"messageId":"%s","type":"%s","correlationId":"%s","transferId":"%s","producer":"wallet-service",
                 "occurredAt":"%s","payload":%s}"""
                .formatted(messageId, type, correlationId, transferId, Instant.now(), payloadJson);
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setCorrelationId(correlationId.toString());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send("wallet.events", routingKey, new Message(json.getBytes(StandardCharsets.UTF_8), properties));
    }

    /** The next message of a queue as JSON, waiting up to the timeout; fails when none arrives. */
    protected JsonNode next(String queue, Duration timeout) {
        Message message = receive(queue, timeout);
        if (message == null) {
            fail("no message in " + queue + " after " + timeout);
        }
        try {
            return new ObjectMapper().readTree(message.getBody());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- state helpers

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

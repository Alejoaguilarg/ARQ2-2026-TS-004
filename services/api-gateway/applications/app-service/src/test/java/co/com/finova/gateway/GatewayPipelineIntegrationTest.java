package co.com.finova.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// Full path with nothing faked inside the Gateway: RS256 tokens checked against a JWKS endpoint,
// revocation, financial session and idempotency in a real Redis, then the proxy to an upstream stub.
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
// Spring Boot tests turn metric exporters off; this keeps /actuator/prometheus as in the running app.
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayPipelineIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    private static final GenericContainer<?> RABBITMQ =
            new GenericContainer<>("rabbitmq:4.1-management-alpine").withExposedPorts(5672);
    private static final String AUDIT_QUEUE = "test.audit.attempts";

    private static final String USER = "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c";
    private static final String DEVICE = "device-1";
    private static final String BODY = "{\"recipientUserId\":\"r-1\",\"amount\":\"150000.00\",\"currency\":\"COP\"}";
    private static final RSAKey KEY = rsaKey();

    private static final AtomicInteger TRANSFER_CALLS = new AtomicInteger();
    private static final AtomicReference<String> TRANSFER_BODY = new AtomicReference<>();
    private static final Map<String, String> UPSTREAM_HEADERS = new ConcurrentHashMap<>();
    private static final AtomicBoolean TRANSFER_SLOW_ONCE = new AtomicBoolean();
    private static final HttpServer UPSTREAM = startUpstream();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String upstream = "http://localhost:" + UPSTREAM.getAddress().getPort();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
        registry.add("TRANSFER_SERVICE_URI", () -> upstream);
        registry.add("WALLET_SERVICE_URI", () -> upstream);
        registry.add("JWKS_URI", () -> upstream + "/.well-known/jwks.json");
        registry.add("IDENTITY_SERVICE_URI", () -> "http://localhost:" + closedPort());
        registry.add("spring.http.client.read-timeout", () -> "1s");
    }

    // The broker's topology (infra/rabbitmq/definitions.json) reduced to what the Gateway publishes to.
    private static RabbitTemplate auditQueue() {
        CachingConnectionFactory connections =
                new CachingConnectionFactory(RABBITMQ.getHost(), RABBITMQ.getMappedPort(5672));
        RabbitAdmin admin = new RabbitAdmin(connections);
        TopicExchange exchange = new TopicExchange("audit.events");
        Queue queue = new Queue(AUDIT_QUEUE);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("audit.attempt"));
        return new RabbitTemplate(connections);
    }

    // The AttemptRecorded messages of one Idempotency-Key, by reason; other tests' messages are skipped.
    private static Map<String, JsonNode> auditedAttempts(String key, int expected) throws IOException {
        RabbitTemplate rabbit = auditQueue();
        Map<String, JsonNode> attempts = new HashMap<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (attempts.size() < expected && System.currentTimeMillis() < deadline) {
            Message message = rabbit.receive(AUDIT_QUEUE, 500);
            if (message != null) {
                JsonNode envelope = new ObjectMapper().readTree(message.getBody());
                if (key.equals(envelope.path("payload").path("idempotencyKey").asText())) {
                    attempts.put(envelope.path("payload").path("reason").asText(), envelope);
                }
            }
        }
        return attempts;
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @BeforeEach
    void reset() {
        TRANSFER_CALLS.set(0);
        UPSTREAM_HEADERS.clear();
        TRANSFER_SLOW_ONCE.set(false);
    }

    @Test
    void upstreamDownIs502UpstreamUnavailable() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("\"UPSTREAM_UNAVAILABLE\"");
    }

    @Test
    void upstreamTimeoutIs504AndFreesTheIdempotencyKeyForTheRetry() throws Exception {
        String accessToken = accessToken(UUID.randomUUID().toString());
        String session = financialSession();
        String key = UUID.randomUUID().toString();
        TRANSFER_SLOW_ONCE.set(true);

        HttpResponse<String> timedOut = postTransfer(accessToken, session, key, BODY);
        HttpResponse<String> retry = postTransfer(accessToken, session, key, BODY);

        assertThat(timedOut.statusCode()).isEqualTo(504);
        assertThat(timedOut.body()).contains("\"UPSTREAM_UNAVAILABLE\"");
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).isEmpty();
    }

    @Test
    void routesOnceAndReplaysTheSameTransfer() throws Exception {
        String accessToken = accessToken(UUID.randomUUID().toString());
        String session = financialSession();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = postTransfer(accessToken, session, key, BODY);
        HttpResponse<String> retry = postTransfer(accessToken, session, key, BODY);
        HttpResponse<String> reused = postTransfer(accessToken, session, key, BODY.replace("150000.00", "1.00"));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(TRANSFER_BODY.get()).isEqualTo(BODY);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(TRANSFER_CALLS.get()).isOne();
        assertThat(metrics()).contains("finova_gateway_idempotent_replays_total")
                .contains("finova_gateway_rejections_total{code=\"IDEMPOTENCY_KEY_REUSED\",status=\"422\"}");
    }

    @Test
    void rejectionsAreExportedToPrometheusByCode() throws Exception {
        send(HttpRequest.newBuilder(uri("/wallets/me/balance")).GET());
        send(HttpRequest.newBuilder(uri("/wallets/me/balance")).header("Authorization", "Bearer forged").GET());

        assertThat(metrics()).containsPattern("finova_gateway_rejections_total\\{code=\"UNAUTHORIZED\",status=\"401\"\\} [1-9]");
    }

    @Test
    void theCountersBehindTheAlertsExistAtZeroBeforeTheFirstEvent() throws Exception {
        assertThat(metrics())
                .contains("finova_gateway_rejections_total{code=\"IDEMPOTENCY_STORE_UNAVAILABLE\",status=\"503\"} 0.0")
                .contains("finova_gateway_rejections_total{code=\"SECURITY_STORE_UNAVAILABLE\",status=\"503\"} 0.0");
    }

    private String metrics() throws Exception {
        return send(HttpRequest.newBuilder(uri("/actuator/prometheus")).GET()).body();
    }

    @Test
    void upstreamReceivesTheIdentityFromTheTokenNotFromTheClient() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/wallets/me/balance"))
                .header("Authorization", "Bearer " + accessToken(UUID.randomUUID().toString()))
                .header("X-User-Id", "someone-else")
                .header("X-User-Roles", "AUDITOR")
                .GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(UPSTREAM_HEADERS).containsEntry("x-user-id", USER)
                .containsEntry("x-device-id", DEVICE)
                .containsEntry("x-user-roles", "USER");
        assertThat(UPSTREAM_HEADERS.get("x-correlation-id")).matches("[0-9a-f-]{36}")
                .isEqualTo(response.headers().firstValue("X-Correlation-Id").orElseThrow());
    }

    @Test
    void theCorrelationIdOfTheResponseIsInTheErrorBodyAndInTheLogs(CapturedOutput logs) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        String id = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(response.body()).contains("\"correlationId\":\"" + id + "\"");
        // UpstreamErrorFilter logs the unreachable Identity; the line carries the id (logging.pattern.correlation).
        assertThat(logs.getOut()).containsPattern("\\[" + id + "\\].*Upstream unavailable")
                // [traceId,spanId] right after it: Grafana links the line to its trace in Tempo.
                .containsPattern("\\[" + id + "\\] \\[[0-9a-f]{32},[0-9a-f]{16}\\]");
    }

    @Test
    void f14_revokedSessionIsRejectedBeforeReachingAnyService() throws Exception {
        String sid = UUID.randomUUID().toString();
        REDIS.execInContainer("redis-cli", "SET", "revoked:sid:" + sid, "1", "EX", "900");

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/wallets/me/balance"))
                .header("Authorization", "Bearer " + accessToken(sid))
                .GET());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("TOKEN_REVOKED");
        assertThat(UPSTREAM_HEADERS).isEmpty();
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/wallets/me/balance"))
                .header("Authorization", "Bearer " + sign(rsaKey(), accessClaims(UUID.randomUUID().toString())))
                .GET());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(UPSTREAM_HEADERS).isEmpty();
    }

    @Test
    void f110_financialSessionCannotAuthorizeASecondTransferIntent() throws Exception {
        String accessToken = accessToken(UUID.randomUUID().toString());
        String session = financialSession();

        HttpResponse<String> first = postTransfer(accessToken, session, UUID.randomUUID().toString(), BODY);
        HttpResponse<String> second = postTransfer(accessToken, session, UUID.randomUUID().toString(), BODY);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(403);
        assertThat(second.body()).contains("FINANCIAL_SESSION_USED");
        assertThat(TRANSFER_CALLS.get()).isOne();
    }

    @Test
    void f51_theGatewayAuditsTheTransfersItAnswersItself() throws Exception {
        auditQueue();
        String accessToken = accessToken(UUID.randomUUID().toString());
        String session = financialSession();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = postTransfer(accessToken, session, key, BODY);
        postTransfer(accessToken, session, key, BODY);
        postTransfer(accessToken, session, key, BODY.replace("150000.00", "1.00"));
        postTransfer(accessToken, null, key, BODY);

        Map<String, JsonNode> attempts = auditedAttempts(key, 3);
        assertThat(attempts).containsOnlyKeys("DUPLICATE_REPLAYED", "KEY_REUSED", "FINANCIAL_SESSION_REQUIRED");
        JsonNode replay = attempts.get("DUPLICATE_REPLAYED");
        assertThat(replay.path("producer").asText()).isEqualTo("api-gateway");
        assertThat(replay.path("payload").path("source").asText()).isEqualTo("GATEWAY");
        assertThat(replay.path("payload").path("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(replay.path("payload").path("userId").asText()).isEqualTo(USER);
        assertThat(replay.path("payload").path("transferId").asText())
                .isEqualTo(new ObjectMapper().readTree(first.body()).path("transferId").asText());
        assertThat(attempts.get("KEY_REUSED").path("payload").path("httpStatus").asInt()).isEqualTo(422);
        assertThat(attempts.get("FINANCIAL_SESSION_REQUIRED").path("payload").path("outcome").asText())
                .isEqualTo("REJECTED");
    }

    @Test
    void f17_transferWithoutFinancialSessionIsRejected() throws Exception {
        HttpResponse<String> response = postTransfer(accessToken(UUID.randomUUID().toString()), null,
                UUID.randomUUID().toString(), BODY);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("FINANCIAL_SESSION_REQUIRED");
        assertThat(TRANSFER_CALLS.get()).isZero();
    }

    @Test
    void pathVariantsCannotSkipTheTransferChecks() throws Exception {
        String accessToken = accessToken(UUID.randomUUID().toString());
        for (String path : List.of("/transfers;x=1", "//transfers", "/transfers/", "/wallets/../transfers")) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(BODY)));

            assertThat(response.statusCode()).as(path).isNotEqualTo(202);
        }
        assertThat(TRANSFER_CALLS.get()).isZero();
    }

    @Test
    void pathVariantsCannotSkipTheAuditorRole() throws Exception {
        String accessToken = accessToken(UUID.randomUUID().toString());
        for (String path : List.of("/audit", "/audit/", "/audit/records;x=1", "//audit/records", "/wallets/../audit/records")) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path))
                    .header("Authorization", "Bearer " + accessToken).GET());

            assertThat(response.statusCode()).as(path).isEqualTo(403);
        }
    }

    private HttpResponse<String> postTransfer(String accessToken, String session, String key, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/transfers"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("X-Financial-Session", session);
        }
        return send(request);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String accessToken(String sid) {
        return sign(KEY, accessClaims(sid));
    }

    private static JWTClaimsSet accessClaims(String sid) {
        return new JWTClaimsSet.Builder().issuer("finova-identity").subject(USER).jwtID(UUID.randomUUID().toString())
                .claim("did", DEVICE).claim("sid", sid).claim("roles", List.of("USER"))
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900))).build();
    }

    private static String financialSession() {
        return sign(KEY, new JWTClaimsSet.Builder().issuer("finova-identity").subject(USER)
                .jwtID(UUID.randomUUID().toString()).claim("did", DEVICE).claim("scope", "transfer:create")
                .claim("method", "PIN").issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("2026-10").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // One stub plays Identity's JWKS, Transfer and Wallet.
    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/.well-known/jwks.json",
                    exchange -> answer(exchange, 200, new JWKSet(KEY.toPublicJWK()).toString()));
            server.createContext("/transfers", exchange -> {
                TRANSFER_CALLS.incrementAndGet();
                if (TRANSFER_SLOW_ONCE.getAndSet(false)) {
                    sleep(2_000);
                }
                TRANSFER_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                answer(exchange, 202, "{\"transferId\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            });
            server.createContext("/wallets", exchange -> {
                exchange.getRequestHeaders().forEach((name, values) -> UPSTREAM_HEADERS.put(name.toLowerCase(), values.get(0)));
                answer(exchange, 200, "{\"available\":\"1.00\"}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int closedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

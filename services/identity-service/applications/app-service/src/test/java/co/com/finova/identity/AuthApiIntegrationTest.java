package co.com.finova.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The whole service on real PostgreSQL (Flyway, demo users) and Redis, called over HTTP.
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
// Spring Boot tests turn metric exporters off; this keeps /actuator/prometheus as in the running app.
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiIntegrationTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void f11_demoUserLogsInAndGetsATokenTheGatewayCanVerify() throws Exception {
        String device = "ios-" + UUID.randomUUID();
        HttpResponse<String> response = post("/auth/login", login("Ana@Example.com", "Finova2026!", device));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode pair = JSON.readTree(response.body());
        assertThat(pair.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(pair.get("refreshExpiresIn").asLong()).isEqualTo(604800);

        JWTClaimsSet claims = verifyWithJwks(pair.get("accessToken").asText());
        assertThat(claims.getIssuer()).isEqualTo("finova-identity");
        assertThat(claims.getSubject()).isEqualTo("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
        assertThat(claims.getStringClaim("did")).isEqualTo(device);
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM devices WHERE device_id = ?", Integer.class, device)).isOne();
        // Only the hash of the refresh token is stored.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class,
                pair.get("refreshToken").asText())).isZero();
    }

    @Test
    void wrongPasswordIs401InvalidCredentials() throws Exception {
        HttpResponse<String> response = post("/auth/login", login("ana@example.com", "wrong", "ios-1"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void f12_f13_refreshRotatesAndReuseRevokesTheSession() throws Exception {
        String device = "ios-" + UUID.randomUUID();
        JsonNode first = JSON.readTree(post("/auth/login", login("beto@example.com", "Finova2026!", device)).body());
        String sid = verifyWithJwks(first.get("accessToken").asText()).getStringClaim("sid");

        HttpResponse<String> rotated = post("/auth/refresh", refresh(first.get("refreshToken").asText(), device));
        assertThat(rotated.statusCode()).isEqualTo(200);
        JsonNode second = JSON.readTree(rotated.body());
        assertThat(verifyWithJwks(second.get("accessToken").asText()).getStringClaim("sid")).isEqualTo(sid);

        HttpResponse<String> reused = post("/auth/refresh", refresh(first.get("refreshToken").asText(), device));
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(reused.body()).get("code").asText()).isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(redis.opsForValue().get("revoked:sid:" + sid)).isEqualTo("1");
        assertThat(redis.getExpire("revoked:sid:" + sid)).isBetween(890L, 900L);

        HttpResponse<String> afterRevoke = post("/auth/refresh", refresh(second.get("refreshToken").asText(), device));
        assertThat(JSON.readTree(afterRevoke.body()).get("code").asText()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void logoutEndsTheSessionEverywhere() throws Exception {
        String device = "ios-" + UUID.randomUUID();
        JsonNode pair = JSON.readTree(post("/auth/login", login("ana@example.com", "Finova2026!", device)).body());
        JWTClaimsSet claims = verifyWithJwks(pair.get("accessToken").asText());

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/logout"))
                        .header("Authorization", "Bearer " + pair.get("accessToken").asText())
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(redis.opsForValue().get("revoked:jti:" + claims.getJWTID())).isEqualTo("1");
        assertThat(redis.getExpire("revoked:jti:" + claims.getJWTID())).isBetween(890L, 900L);
        assertThat(redis.opsForValue().get("revoked:sid:" + claims.getStringClaim("sid"))).isEqualTo("1");
        HttpResponse<String> refreshAfter = post("/auth/refresh", refresh(pair.get("refreshToken").asText(), device));
        assertThat(JSON.readTree(refreshAfter.body()).get("code").asText()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void f16_stepUpWithPinOrOtpIssuesAFinancialSessionForTheDevice() throws Exception {
        String device = "ios-" + UUID.randomUUID();
        String accessToken = JSON.readTree(post("/auth/login", login("ana@example.com", "Finova2026!", device)).body())
                .get("accessToken").asText();

        HttpResponse<String> pin = stepUp(accessToken, "PIN", "4821");
        assertThat(pin.statusCode()).isEqualTo(201);
        JsonNode issued = JSON.readTree(pin.body());
        assertThat(issued.get("expiresIn").asLong()).isEqualTo(300);
        JWTClaimsSet session = verifyWithJwks(issued.get("financialSessionToken").asText());
        assertThat(session.getSubject()).isEqualTo("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
        assertThat(session.getStringClaim("did")).isEqualTo(device);
        assertThat(session.getStringClaim("scope")).isEqualTo("transfer:create");
        assertThat(session.getStringClaim("method")).isEqualTo("PIN");

        // ana's demo secret from the README, so this proves seed, key and verifier agree.
        assertThat(stepUp(accessToken, "OTP", totp("JOJYF2MFLYMVTZVHPQXBEZXZPG3BSRZ2")).statusCode()).isEqualTo(201);

        HttpResponse<String> wrong = stepUp(accessToken, "PIN", "0000");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(wrong.body()).get("code").asText()).isEqualTo("STEP_UP_FAILED");
    }

    @Test
    void rejectionsAreLoggedWithTheGatewayCorrelationIdAndWithoutSecrets(CapturedOutput logs) throws Exception {
        String loginId = UUID.randomUUID().toString();
        String stepUpId = UUID.randomUUID().toString();
        String ana = "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c";

        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", loginId)
                        .POST(HttpRequest.BodyPublishers.ofString(login("ana@example.com", "Wr0ng-Secret!", "ios-1")))
                        .build(), HttpResponse.BodyHandlers.ofString());
        String accessToken = JSON.readTree(post("/auth/login", login("ana@example.com", "Finova2026!", "ios-1")).body())
                .get("accessToken").asText();
        HttpResponse<String> stepUp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/financial-session"))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", stepUpId)
                        .header("X-User-Id", ana)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"method\":\"PIN\",\"credential\":\"9731\",\"scope\":\"transfer:create\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(login.body()).get("correlationId").asText()).isEqualTo(loginId);
        assertThat(stepUp.statusCode()).isEqualTo(401);
        assertThat(logs.getOut())
                .containsPattern("\\[" + loginId + "\\].*Login rejected: invalid credentials")
                .containsPattern("\\[" + stepUpId + "\\].*Step-up failed: wrong PIN for user " + ana)
                .doesNotContain("Wr0ng-Secret!", "ana@example.com", "9731", accessToken);
        String metrics = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/prometheus")).build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(metrics).containsPattern("finova_identity_security_events_total\\{event=\"login_failed\"\\} [1-9]")
                .containsPattern("finova_identity_security_events_total\\{event=\"step_up_failed\"\\} [1-9]");
    }

    private HttpResponse<String> stepUp(String accessToken, String method, String credential) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/financial-session"))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"" + method + "\",\"credential\":\""
                                + credential + "\",\"scope\":\"transfer:create\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // What an authenticator app shows now (RFC 6238, written out here so the test does not reuse the code under test).
    private static String totp(String base32Secret) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream secret = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : base32Secret.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                secret.write(buffer >> (bits - 8));
                bits -= 8;
            }
        }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA1"));
        byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(java.time.Instant.now().getEpochSecond() / 30).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = java.nio.ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
        return String.format("%06d", binary % 1_000_000);
    }

    private JWTClaimsSet verifyWithJwks(String token) throws Exception {
        HttpResponse<String> jwks = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/.well-known/jwks.json")).build(),
                HttpResponse.BodyHandlers.ofString());
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(JWKSet.parse(jwks.body()))));
        return processor.process(token, null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String login(String email, String password, String device) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"deviceId\":\"" + device + "\"}";
    }

    private static String refresh(String token, String device) {
        return "{\"refreshToken\":\"" + token + "\",\"deviceId\":\"" + device + "\"}";
    }
}

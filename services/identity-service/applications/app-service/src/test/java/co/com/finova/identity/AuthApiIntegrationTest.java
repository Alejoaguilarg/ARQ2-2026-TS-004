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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

package co.com.finova.gateway.jwt;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.FinancialSessionToken;
import co.com.finova.gateway.model.security.InvalidTokenException;
import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwksTokenVerifierTest {

    private static final String ISSUER = "finova-identity";
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final RSAKey KEY = rsaKey("2026-10");

    private final JwksTokenVerifier verifier = new JwksTokenVerifier(
            new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())), ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validAccessTokenYieldsItsClaims() {
        AccessToken token = verifier.verifyAccessToken(sign(KEY, accessClaims().build()));

        assertThat(token).isEqualTo(new AccessToken("jti-1", "u-1", "d-1", "sid-1", List.of("USER", "AUDITOR")));
    }

    @Test
    void validFinancialSessionYieldsItsClaims() {
        FinancialSessionToken token = verifier.verifyFinancialSession(sign(KEY, financialClaims().build()));

        assertThat(token).isEqualTo(new FinancialSessionToken("fs-1", "u-1", "d-1", "transfer:create"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsInvalid() {
        assertInvalid(sign(rsaKey("2026-10"), accessClaims().build()), false);
    }

    @Test
    void tokenFromAnotherIssuerIsInvalid() {
        assertInvalid(sign(KEY, accessClaims().issuer("someone-else").build()), false);
    }

    @Test
    void expiredTokenIsReportedAsExpired() {
        assertInvalid(sign(KEY, accessClaims().expirationTime(Date.from(NOW)).build()), true);
    }

    @Test
    void tokenWithoutExpIsInvalid() {
        assertInvalid(sign(KEY, accessClaims().expirationTime(null).build()), false);
    }

    @Test
    void unsignedTokenIsInvalid() {
        assertInvalid(new PlainJWT(accessClaims().build()).serialize(), false);
    }

    @Test
    void hmacTokenIsInvalidEvenIfSignedWithThePublicKeyBytes() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), accessClaims().build());
        jwt.sign(new MACSigner(KEY.toRSAPublicKey().getEncoded()));

        assertInvalid(jwt.serialize(), false);
    }

    @Test
    void garbageIsInvalid() {
        assertInvalid("not-a-jwt", false);
    }

    @Test
    void accessTokenMissingSidIsInvalid() {
        assertInvalid(sign(KEY, accessClaims().claim("sid", null).build()), false);
    }

    @Test
    void financialSessionIsNotAcceptedAsAccessToken() {
        assertInvalid(sign(KEY, financialClaims().claim("roles", List.of("USER")).claim("sid", "sid-1").build()), false);
    }

    @Test
    void financialSessionWithoutScopeIsInvalid() {
        String token = sign(KEY, financialClaims().claim("scope", null).build());

        assertThatThrownBy(() -> verifier.verifyFinancialSession(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredFinancialSessionIsReportedAsExpired() {
        String token = sign(KEY, financialClaims().expirationTime(Date.from(NOW.minusSeconds(1))).build());

        assertThatThrownBy(() -> verifier.verifyFinancialSession(token))
                .isInstanceOfSatisfying(InvalidTokenException.class, e -> assertThat(e.isExpired()).isTrue());
    }

    @Test
    void rotatedKeyIsFetchedFromTheJwksWhenAnUnknownKidArrives() throws Exception {
        RSAKey next = rsaKey("2026-11");
        AtomicReference<JWKSet> published = new AtomicReference<>(new JWKSet(KEY.toPublicJWK()));
        HttpServer jwks = serve(published);
        try {
            JwksTokenVerifier remote = new JwksTokenVerifier(
                    JWKSourceBuilder.create(URI.create(url(jwks)).toURL()).rateLimited(false).build(),
                    ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(remote.verifyAccessToken(sign(KEY, accessClaims().build())).userId()).isEqualTo("u-1");

            published.set(new JWKSet(List.of(KEY.toPublicJWK(), next.toPublicJWK())));

            assertThat(remote.verifyAccessToken(sign(next, accessClaims().build())).userId()).isEqualTo("u-1");
        } finally {
            jwks.stop(0);
        }
    }

    @Test
    void unreachableJwksFailsClosed() throws Exception {
        HttpServer jwks = serve(new AtomicReference<>(new JWKSet()));
        String url = url(jwks);
        jwks.stop(0);
        JwksTokenVerifier remote = new JwksTokenVerifier(url, ISSUER);

        assertThatThrownBy(() -> remote.verifyAccessToken(sign(KEY, accessClaims().build())))
                .isInstanceOf(SecurityStoreUnavailableException.class);
    }

    private void assertInvalid(String token, boolean expired) {
        assertThatThrownBy(() -> verifier.verifyAccessToken(token))
                .isInstanceOfSatisfying(InvalidTokenException.class, e -> assertThat(e.isExpired()).isEqualTo(expired));
    }

    private static JWTClaimsSet.Builder accessClaims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("u-1").jwtID("jti-1")
                .claim("did", "d-1").claim("sid", "sid-1").claim("roles", List.of("USER", "AUDITOR"))
                .issueTime(Date.from(NOW.minusSeconds(60))).expirationTime(Date.from(NOW.plusSeconds(840)));
    }

    private static JWTClaimsSet.Builder financialClaims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("u-1").jwtID("fs-1")
                .claim("did", "d-1").claim("scope", "transfer:create").claim("method", "PIN")
                .issueTime(Date.from(NOW.minusSeconds(10))).expirationTime(Date.from(NOW.plusSeconds(290)));
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

    private static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer serve(AtomicReference<JWKSet> published) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = published.get().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }
}

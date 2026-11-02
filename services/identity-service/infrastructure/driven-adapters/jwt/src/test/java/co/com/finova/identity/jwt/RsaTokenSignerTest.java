package co.com.finova.identity.jwt;

import co.com.finova.identity.model.token.SignedToken;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaTokenSignerTest {

    private static final UUID USER = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final UUID SESSION = UUID.fromString("0b6f1c2e-3d4a-4b5c-8d9e-0f1a2b3c4d5e");
    // Not on a whole second, to check iat and exp are truncated the way JWT stores them.
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00.750Z");

    private final RsaTokenSigner signer = new RsaTokenSigner("finova-identity", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void accessTokenCarriesTheClaimsTheGatewayRequires() throws Exception {
        SignedToken token = signer.signAccessToken(USER, "ios-7F3A9C", SESSION, List.of("USER", "AUDITOR"));

        JWTClaimsSet claims = verifyWithPublishedKeys(token.value());

        assertThat(claims.getIssuer()).isEqualTo("finova-identity");
        assertThat(claims.getSubject()).isEqualTo(USER.toString());
        assertThat(claims.getStringClaim("did")).isEqualTo("ios-7F3A9C");
        assertThat(claims.getStringClaim("sid")).isEqualTo(SESSION.toString());
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER", "AUDITOR");
        assertThat(claims.getJWTID()).isEqualTo(token.jti());
        assertThat(claims.getClaim("scope")).isNull();
        assertThat(claims.getIssueTime().toInstant()).isEqualTo("2026-11-02T12:00:00Z");
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo("2026-11-02T12:15:00Z").isEqualTo(token.expiresAt());
    }

    @Test
    void financialSessionIsShortLivedAndCannotPassForALogin() throws Exception {
        SignedToken token = signer.signFinancialSession(USER, "ios-7F3A9C", "transfer:create", "PIN");

        JWTClaimsSet claims = verifyWithPublishedKeys(token.value());

        assertThat(claims.getSubject()).isEqualTo(USER.toString());
        assertThat(claims.getStringClaim("did")).isEqualTo("ios-7F3A9C");
        assertThat(claims.getStringClaim("scope")).isEqualTo("transfer:create");
        assertThat(claims.getStringClaim("method")).isEqualTo("PIN");
        assertThat(claims.getJWTID()).isEqualTo(token.jti());
        assertThat(claims.getClaim("sid")).isNull();
        assertThat(claims.getClaim("roles")).isNull();
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo("2026-11-02T12:05:00Z").isEqualTo(token.expiresAt());
    }

    @Test
    void everyTokenGetsItsOwnJti() {
        String first = signer.signAccessToken(USER, "d", SESSION, List.of("USER")).jti();
        String second = signer.signAccessToken(USER, "d", SESSION, List.of("USER")).jti();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void headerNamesThePublishedKey() throws Exception {
        SignedJWT jwt = SignedJWT.parse(signer.signAccessToken(USER, "d", SESSION, List.of("USER")).value());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(JWKSet.parse(signer.publicKeys()).getKeys().get(0).getKeyID());
    }

    @Test
    void publishedKeySetHasNoPrivateParts() {
        @SuppressWarnings("unchecked")
        Map<String, Object> key = ((List<Map<String, Object>>) signer.publicKeys().get("keys")).get(0);

        assertThat(key).containsEntry("kty", "RSA").containsEntry("use", "sig").containsEntry("alg", "RS256")
                .containsKeys("kid", "n", "e")
                .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    }

    @Test
    void tokenFromAnotherInstanceDoesNotVerify() {
        String foreign = new RsaTokenSigner("finova-identity").signAccessToken(USER, "d", SESSION, List.of("USER")).value();

        assertThatThrownBy(() -> verifyWithPublishedKeys(foreign)).isInstanceOf(Exception.class);
    }

    @Test
    void ownAccessTokenVerifiesForLogout() {
        SignedToken token = signer.signAccessToken(USER, "d", SESSION, List.of("USER"));

        assertThat(signer.verifyAccessToken(token.value())).hasValueSatisfying(claims -> {
            assertThat(claims.jti()).isEqualTo(token.jti());
            assertThat(claims.sessionId()).isEqualTo(SESSION);
            assertThat(claims.userId()).isEqualTo(USER);
            assertThat(claims.expiresAt()).isEqualTo(token.expiresAt());
        });
    }

    @Test
    void financialSessionIsNotAcceptedForLogout() {
        assertThat(signer.verifyAccessToken(signer.signFinancialSession(USER, "d", "transfer:create", "PIN").value())).isEmpty();
    }

    @Test
    void expiredAccessTokenIsNotAccepted() {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        RsaTokenSigner moving = new RsaTokenSigner("finova-identity", new Clock() {
            @Override
            public Instant instant() {
                return now.get();
            }

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        });
        String token = moving.signAccessToken(USER, "d", SESSION, List.of("USER")).value();

        now.set(Instant.parse("2026-11-02T12:14:59Z"));
        assertThat(moving.verifyAccessToken(token)).isPresent();
        now.set(Instant.parse("2026-11-02T12:15:00Z"));
        assertThat(moving.verifyAccessToken(token)).isEmpty();
    }

    @Test
    void foreignOrMalformedTokensAreNotAccepted() {
        RsaTokenSigner other = new RsaTokenSigner("finova-identity", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(signer.verifyAccessToken(other.signAccessToken(USER, "d", SESSION, List.of("USER")).value())).isEmpty();
        assertThat(signer.verifyAccessToken("not-a-jwt")).isEmpty();
    }

    // Same check the Gateway's JwksTokenVerifier runs: RS256 only, key from the published set.
    private JWTClaimsSet verifyWithPublishedKeys(String token) throws Exception {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(JWKSet.parse(signer.publicKeys()))));
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        return processor.process(token, null);
    }
}

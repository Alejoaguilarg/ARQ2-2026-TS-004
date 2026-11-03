package co.com.finova.identity.jwt;

import co.com.finova.identity.model.token.AccessTokenClaims;
import co.com.finova.identity.model.token.SignedToken;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

// The signing key comes from finova.security.signing-key-file (JWT_SIGNING_KEY_FILE), created there on first start
// (SigningKeyFile): restarts keep every issued token valid and every instance signs with the same key. Without a
// file the key lives in memory and changes on every restart, which is only fit for tests and a quick local run.
@Component
public class RsaTokenSigner implements TokenSigner {

    private final RSAKey key;
    private final DefaultJWTProcessor<SecurityContext> verifier = new DefaultJWTProcessor<>();
    private final String issuer;
    private final Clock clock;

    @Autowired
    public RsaTokenSigner(@Value("${finova.security.issuer:finova-identity}") String issuer,
                          @Value("${finova.security.signing-key-file:}") String keyFile) {
        this(issuer, Clock.systemUTC(),
                keyFile.isBlank() ? SigningKeyFile.inMemory() : SigningKeyFile.loadOrCreate(Path.of(keyFile)));
    }

    RsaTokenSigner(String issuer) {
        this(issuer, Clock.systemUTC());
    }

    RsaTokenSigner(String issuer, Clock clock) {
        this(issuer, clock, SigningKeyFile.inMemory());
    }

    RsaTokenSigner(String issuer, Clock clock, RSAKey key) {
        this.issuer = issuer;
        this.clock = clock;
        this.key = key;
        verifier.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()))));
        // Only Identity holds the key, so a valid signature already means our iss; exp is checked below.
        verifier.setJWTClaimsSetVerifier((claims, context) -> {
        });
    }

    @Override
    public SignedToken signAccessToken(UUID userId, String deviceId, UUID sessionId, List<String> roles) {
        return sign(userId, deviceId, ACCESS_TOKEN_TTL, claims -> claims
                .claim("sid", sessionId.toString())
                .claim("roles", roles));
    }

    @Override
    public SignedToken signFinancialSession(UUID userId, String deviceId, String scope, String method) {
        return sign(userId, deviceId, FINANCIAL_SESSION_TTL, claims -> claims
                .claim("scope", scope)
                .claim("method", method));
    }

    @Override
    public Optional<AccessTokenClaims> verifyAccessToken(String token) {
        try {
            JWTClaimsSet claims = verifier.process(token, null);
            String sid = claims.getStringClaim("sid");
            String deviceId = claims.getStringClaim("did");
            if (claims.getExpirationTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(clock.instant())
                    || claims.getClaim("scope") != null || sid == null || deviceId == null || claims.getJWTID() == null
                    || claims.getSubject() == null) {
                return Optional.empty();
            }
            return Optional.of(new AccessTokenClaims(claims.getJWTID(), UUID.fromString(sid),
                    UUID.fromString(claims.getSubject()), deviceId, claims.getExpirationTime().toInstant()));
        } catch (ParseException | BadJOSEException | JOSEException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Object> publicKeys() {
        return new JWKSet(key).toJSONObject(true);
    }

    private SignedToken sign(UUID userId, String deviceId, Duration ttl,
                             UnaryOperator<JWTClaimsSet.Builder> extra) {
        // Whole seconds: JWT times are seconds, so expiresAt must match the exp claim exactly.
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(ttl);
        String jti = UUID.randomUUID().toString();
        JWTClaimsSet claims = extra.apply(new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(userId.toString())
                        .claim("did", deviceId)
                        .jwtID(jti)
                        .issueTime(Date.from(issuedAt))
                        .expirationTime(Date.from(expiresAt)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return new SignedToken(jwt.serialize(), jti, expiresAt);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign token", e);
        }
    }
}

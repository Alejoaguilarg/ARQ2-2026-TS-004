package co.com.finova.identity.jwt;

import co.com.finova.identity.model.token.SignedToken;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

// shortcut: the RSA key lives in memory and changes on every restart, so access tokens die with the
// process (clients just call /auth/refresh: refresh tokens are in identity_db). Load a key from a
// mounted secret when Identity runs more than one instance.
@Component
public class RsaTokenSigner implements TokenSigner {

    private final RSAKey key;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public RsaTokenSigner(@Value("${finova.security.issuer:finova-identity}") String issuer) {
        this(issuer, Clock.systemUTC());
    }

    RsaTokenSigner(String issuer, Clock clock) {
        this.issuer = issuer;
        this.clock = clock;
        try {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate the token signing key", e);
        }
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

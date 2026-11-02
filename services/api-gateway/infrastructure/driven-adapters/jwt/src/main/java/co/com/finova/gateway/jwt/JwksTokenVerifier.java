package co.com.finova.gateway.jwt;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.FinancialSessionToken;
import co.com.finova.gateway.model.security.InvalidTokenException;
import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.model.security.gateways.TokenVerifier;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.List;

// RS256 tokens signed by Identity. Keys come from its JWKS; Nimbus caches them and
// refetches when a token carries an unknown kid (key rotation, spec.md §3.1.3).
@Component
public class JwksTokenVerifier implements TokenVerifier {

    private final ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    private final String issuer;
    private final Clock clock;

    @Autowired
    public JwksTokenVerifier(@Value("${finova.security.jwks-uri}") String jwksUri,
                             @Value("${finova.security.issuer}") String issuer) throws MalformedURLException {
        this(JWKSourceBuilder.create(URI.create(jwksUri).toURL()).build(), issuer, Clock.systemUTC());
    }

    JwksTokenVerifier(JWKSource<SecurityContext> keys, String issuer, Clock clock) {
        this.issuer = issuer;
        this.clock = clock;
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // Claims are checked below, so an expired token can be told apart from a forged one.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
    }

    @Override
    public AccessToken verifyAccessToken(String token) {
        JWTClaimsSet claims = verify(token);
        // A financial-session token carries a scope and no sid: it is not a login.
        if (claims.getClaim("scope") != null) {
            throw new InvalidTokenException("Not an access token", false);
        }
        try {
            List<String> roles = claims.getStringListClaim("roles");
            return new AccessToken(required(claims.getJWTID()), required(claims.getSubject()),
                    required(claims.getStringClaim("did")), required(claims.getStringClaim("sid")),
                    roles == null ? List.of() : roles);
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed claims", false);
        }
    }

    @Override
    public FinancialSessionToken verifyFinancialSession(String token) {
        JWTClaimsSet claims = verify(token);
        try {
            return new FinancialSessionToken(required(claims.getJWTID()), required(claims.getSubject()),
                    required(claims.getStringClaim("did")), required(claims.getStringClaim("scope")));
        } catch (ParseException e) {
            throw new InvalidTokenException("Malformed claims", false);
        }
    }

    private JWTClaimsSet verify(String token) {
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (KeySourceException e) {
            // Identity's JWKS is unreachable and no cached key fits: nothing can be verified, fail closed.
            throw new SecurityStoreUnavailableException(e);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new InvalidTokenException("Invalid token: " + e.getMessage(), false);
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new InvalidTokenException("Unexpected issuer", false);
        }
        Date expiresAt = claims.getExpirationTime();
        if (expiresAt == null) {
            throw new InvalidTokenException("Missing exp", false);
        }
        if (!expiresAt.toInstant().isAfter(clock.instant())) {
            throw new InvalidTokenException("Token expired", true);
        }
        return claims;
    }

    private static String required(String claim) {
        if (claim == null || claim.isBlank()) {
            throw new InvalidTokenException("Missing claim", false);
        }
        return claim;
    }
}

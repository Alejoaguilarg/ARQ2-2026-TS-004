package co.com.finova.identity.model.token.gateways;

import co.com.finova.identity.model.token.SignedToken;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Signs the tokens the Gateway verifies (spec.md AccessTokenClaims and FinancialSession).
public interface TokenSigner {

    Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    Duration FINANCIAL_SESSION_TTL = Duration.ofMinutes(5);

    // Claims: iss, sub, did, sid, jti, roles, iat, exp.
    SignedToken signAccessToken(UUID userId, String deviceId, UUID sessionId, List<String> roles);

    // Claims: iss, sub, did, jti, scope, method, iat, exp. No sid or roles, so it is never a login.
    SignedToken signFinancialSession(UUID userId, String deviceId, String scope, String method);

    // The JSON Web Key Set served at /.well-known/jwks.json: public keys only.
    Map<String, Object> publicKeys();
}

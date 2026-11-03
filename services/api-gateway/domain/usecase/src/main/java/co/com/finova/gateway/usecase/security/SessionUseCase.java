package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.FinancialSessionToken;
import co.com.finova.gateway.model.security.InvalidTokenException;
import co.com.finova.gateway.model.security.gateways.SessionStore;
import co.com.finova.gateway.model.security.gateways.TokenVerifier;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

// spec.md §3.1: authentication (step 1) and single-use financial session (step 3).
// Store failures surface as SecurityStoreUnavailableException so callers fail closed.
@RequiredArgsConstructor
public class SessionUseCase {

    public static final String TRANSFER_SCOPE = "transfer:create";

    public sealed interface Authentication {
        record Authenticated(AccessToken token) implements Authentication {
        }

        record Unauthorized() implements Authentication {
        }

        record Revoked() implements Authentication {
        }
    }

    public enum FinancialSessionCheck { ACCEPTED, INVALID, EXPIRED, USED }

    private final TokenVerifier tokens;
    private final SessionStore sessions;

    public Authentication authenticate(String bearerToken) {
        AccessToken token;
        try {
            token = tokens.verifyAccessToken(bearerToken);
        } catch (InvalidTokenException e) {
            return new Authentication.Unauthorized();
        }
        return sessions.isRevoked(token.jti(), token.sessionId())
                ? new Authentication.Revoked()
                : new Authentication.Authenticated(token);
    }

    // A financial session serves one transfer intent: a retry with the same Idempotency-Key may reuse it.
    public FinancialSessionCheck checkFinancialSession(String sessionToken, AccessToken user, String idempotencyKey) {
        FinancialSessionToken session;
        try {
            session = tokens.verifyFinancialSession(sessionToken);
        } catch (InvalidTokenException e) {
            return e.isExpired() ? FinancialSessionCheck.EXPIRED : FinancialSessionCheck.INVALID;
        }
        if (!TRANSFER_SCOPE.equals(session.scope())
                || !session.userId().equals(user.userId())
                || !session.deviceId().equals(user.deviceId())) {
            return FinancialSessionCheck.INVALID;
        }
        Optional<String> usedBy = sessions.claimFinancialSession(session.jti(), idempotencyKey);
        return usedBy.isEmpty() || usedBy.get().equals(idempotencyKey)
                ? FinancialSessionCheck.ACCEPTED
                : FinancialSessionCheck.USED;
    }
}

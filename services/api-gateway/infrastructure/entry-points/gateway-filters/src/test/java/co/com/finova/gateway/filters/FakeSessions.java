package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.FinancialSessionToken;
import co.com.finova.gateway.model.security.InvalidTokenException;
import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.model.security.gateways.SessionStore;
import co.com.finova.gateway.model.security.gateways.TokenVerifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Tokens are looked up by their string instead of being signed; JwksTokenVerifierTest covers signatures.
final class FakeSessions implements TokenVerifier, SessionStore {

    final Map<String, AccessToken> accessTokens = new HashMap<>();
    final Map<String, FinancialSessionToken> financialSessions = new HashMap<>();
    final Set<String> expired = new HashSet<>();
    final Set<String> revoked = new HashSet<>();
    final Map<String, String> used = new HashMap<>();
    boolean down;

    @Override
    public AccessToken verifyAccessToken(String token) {
        if (expired.contains(token)) {
            throw new InvalidTokenException("expired", true);
        }
        AccessToken found = accessTokens.get(token);
        if (found == null) {
            throw new InvalidTokenException("unknown", false);
        }
        return found;
    }

    @Override
    public FinancialSessionToken verifyFinancialSession(String token) {
        if (expired.contains(token)) {
            throw new InvalidTokenException("expired", true);
        }
        FinancialSessionToken found = financialSessions.get(token);
        if (found == null) {
            throw new InvalidTokenException("unknown", false);
        }
        return found;
    }

    @Override
    public boolean isRevoked(String jti, String sid) {
        check();
        return revoked.contains(jti) || revoked.contains(sid);
    }

    @Override
    public Optional<String> claimFinancialSession(String jti, String idempotencyKey) {
        check();
        return Optional.ofNullable(used.putIfAbsent(jti, idempotencyKey));
    }

    private void check() {
        if (down) {
            throw new SecurityStoreUnavailableException(new IllegalStateException("redis down"));
        }
    }
}

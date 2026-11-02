package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.gateways.RevocationStore;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;

// spec.md §2.1: logout or refresh-token reuse revokes the access token and its whole session.
@RequiredArgsConstructor
public class RevokeSessionUseCase {

    private final RevocationStore revocations;

    public void execute(String jti, String sid, Instant accessTokenExpiresAt) {
        Duration remaining = Duration.between(Instant.now(), accessTokenExpiresAt);
        if (remaining.isPositive()) {
            revocations.revokeToken(jti, remaining);
        }
        revocations.revokeSession(sid, RevocationStore.SESSION_TTL);
    }
}

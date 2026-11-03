package co.com.finova.identity.model.session.gateways;

import java.time.Duration;

// Both methods throw SecurityStoreUnavailableException when Redis cannot be reached.
public interface RevocationStore {

    // Lifetime of the last access token issued in a session: after it, nothing of the session is valid.
    Duration SESSION_TTL = Duration.ofSeconds(900);

    void revokeToken(String jti, Duration ttl);

    void revokeSession(String sid, Duration ttl);
}

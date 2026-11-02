package co.com.finova.identity.model.session.gateways;

import java.time.Duration;

public interface RevocationStore {

    void revokeToken(String jti, Duration ttl);

    void revokeSession(String sid, Duration ttl);
}

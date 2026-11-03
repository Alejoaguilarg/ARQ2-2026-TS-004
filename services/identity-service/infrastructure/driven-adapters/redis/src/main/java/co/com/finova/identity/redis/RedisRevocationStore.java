package co.com.finova.identity.redis;

import co.com.finova.identity.model.session.SecurityStoreUnavailableException;
import co.com.finova.identity.model.session.gateways.RevocationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Writes the revocation keys of spec.md §2.1 that the Gateway checks on every request.
@Component
@RequiredArgsConstructor
public class RedisRevocationStore implements RevocationStore {

    private final StringRedisTemplate redis;

    @Override
    public void revokeToken(String jti, Duration ttl) {
        set("revoked:jti:" + jti, ttl);
    }

    @Override
    public void revokeSession(String sid, Duration ttl) {
        set("revoked:sid:" + sid, ttl);
    }

    private void set(String key, Duration ttl) {
        try {
            redis.opsForValue().set(key, "1", ttl);
        } catch (DataAccessException e) {
            throw new SecurityStoreUnavailableException(e);
        }
    }
}

package co.com.finova.gateway.redis;

import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.model.security.gateways.SessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisSessionStore implements SessionStore {

    // SET NX that also reports who claimed the session first, in one step (spec.md §3.1 step 3).
    private static final RedisScript<String> CLAIM = RedisScript.of("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
              return existing
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', 300)
            return false
            """, String.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean isRevoked(String jti, String sid) {
        return call(() -> redis.countExistingKeys(List.of("revoked:jti:" + jti, "revoked:sid:" + sid)) > 0);
    }

    @Override
    public Optional<String> claimFinancialSession(String jti, String idempotencyKey) {
        return call(() -> Optional.ofNullable(redis.execute(CLAIM, List.of("fs:used:" + jti), idempotencyKey)));
    }

    private static <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new SecurityStoreUnavailableException(e);
        }
    }
}

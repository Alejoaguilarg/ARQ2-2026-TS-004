package co.com.finova.gateway.redis;

import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.model.security.gateways.CounterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisCounterStore implements CounterStore {

    // INCR and the expiry in one step: a counter left without TTL would block its subject forever.
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public long increment(String key, Duration window) {
        return call(() -> redis.execute(INCREMENT, List.of(key), Long.toString(window.toMillis())));
    }

    @Override
    public Duration timeToLive(String key) {
        return call(() -> Duration.ofMillis(Math.max(0, redis.getExpire(key, TimeUnit.MILLISECONDS))));
    }

    @Override
    public boolean exists(String key) {
        return call(() -> Boolean.TRUE.equals(redis.hasKey(key)));
    }

    @Override
    public void set(String key, Duration ttl) {
        call(() -> {
            redis.opsForValue().set(key, "1", ttl);
            return null;
        });
    }

    @Override
    public void delete(String key) {
        call(() -> redis.delete(key));
    }

    private static <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new SecurityStoreUnavailableException(e);
        }
    }
}

package co.com.finova.wallet.redis;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.gateways.BalanceCache;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisBalanceCache implements BalanceCache {

    // spec.md §2.1: write only if the key is missing or incoming.version > stored.version, atomically.
    private static final RedisScript<Long> PUT_IF_NEWER = RedisScript.of("""
            local stored = redis.call('HGET', KEYS[1], 'version')
            if stored and tonumber(stored) >= tonumber(ARGV[2]) then
              return 0
            end
            redis.call('HSET', KEYS[1], 'walletId', ARGV[1], 'version', ARGV[2],
                       'available', ARGV[3], 'currency', ARGV[4], 'asOf', ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public Optional<Balance> find(UUID userId) {
        Map<Object, Object> hash = redis.opsForHash().entries(key(userId));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Balance(
                UUID.fromString((String) hash.get("walletId")),
                userId,
                new BigDecimal((String) hash.get("available")),
                (String) hash.get("currency"),
                Long.parseLong((String) hash.get("version")),
                Instant.parse((String) hash.get("asOf"))));
    }

    @Override
    public boolean putIfNewer(Balance balance) {
        Long applied = redis.execute(PUT_IF_NEWER, List.of(key(balance.userId())),
                balance.walletId().toString(),
                Long.toString(balance.version()),
                balance.available().toPlainString(),
                balance.currency(),
                balance.asOf().toString());
        return Long.valueOf(1).equals(applied);
    }

    private static String key(UUID userId) {
        return "balance:" + userId;
    }
}

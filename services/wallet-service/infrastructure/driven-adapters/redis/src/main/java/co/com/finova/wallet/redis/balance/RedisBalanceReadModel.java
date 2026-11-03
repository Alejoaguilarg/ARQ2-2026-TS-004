package co.com.finova.wallet.redis.balance;

import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;
import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.balance.gateways.BalanceReadModel;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * balance:{userId} as a Hash {walletId, available, currency, version, asOf} without TTL (spec §2.1).
 * Every write goes through the Lua compare-and-set. Spring sends it with EVALSHA, so Redis runs the
 * cached script and the full text travels only the first time.
 * Failures and timeouts (spring.data.redis.timeout) become BalanceReadModelUnavailableException.
 */
@Repository
public class RedisBalanceReadModel implements BalanceReadModel {

    private static final RedisScript<Long> COMPARE_AND_SET =
            RedisScript.of(new ClassPathResource("scripts/balance-compare-and-set.lua"), Long.class);

    private final StringRedisTemplate redis;

    public RedisBalanceReadModel(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<BalanceSnapshot> find(UUID userId) {
        Map<String, String> hash;
        try {
            hash = redis.<String, String>opsForHash().entries(key(userId));
        } catch (DataAccessException e) {
            throw new BalanceReadModelUnavailableException("read of " + key(userId) + " failed", e);
        }
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BalanceSnapshot(
                UUID.fromString(hash.get("walletId")),
                userId,
                Money.parse(hash.get("available")),
                Currency.fromCode(hash.get("currency")),
                Long.parseLong(hash.get("version")),
                Instant.parse(hash.get("asOf"))));
    }

    @Override
    public boolean storeIfNewer(BalanceSnapshot snapshot) {
        Long written;
        try {
            written = redis.execute(COMPARE_AND_SET, List.of(key(snapshot.userId())),
                    snapshot.walletId().toString(),
                    snapshot.available().toString(),
                    snapshot.currency().name(),
                    Long.toString(snapshot.version()),
                    snapshot.asOf().toString());
        } catch (DataAccessException e) {
            throw new BalanceReadModelUnavailableException("write of " + key(snapshot.userId()) + " failed", e);
        }
        return Long.valueOf(1).equals(written);
    }

    private static String key(UUID userId) {
        return "balance:" + userId;
    }
}

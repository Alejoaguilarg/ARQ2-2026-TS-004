package co.com.finova.gateway.redis;

import co.com.finova.gateway.model.idempotency.IdempotencyRecord;
import co.com.finova.gateway.model.idempotency.IdempotencyStoreUnavailableException;
import co.com.finova.gateway.model.idempotency.gateways.IdempotencyStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Duration TTL = Duration.ofDays(1);
    private static final ObjectMapper JSON = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final StringRedisTemplate redis;

    @Override
    public boolean createIfAbsent(String userId, String idempotencyKey, IdempotencyRecord record) {
        return call(() -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(userId, idempotencyKey), toJson(record), TTL)));
    }

    @Override
    public Optional<IdempotencyRecord> find(String userId, String idempotencyKey) {
        return call(() -> Optional.ofNullable(redis.opsForValue().get(key(userId, idempotencyKey))).map(RedisIdempotencyStore::fromJson));
    }

    @Override
    public void save(String userId, String idempotencyKey, IdempotencyRecord record) {
        call(() -> {
            redis.opsForValue().set(key(userId, idempotencyKey), toJson(record), TTL);
            return null;
        });
    }

    @Override
    public void delete(String userId, String idempotencyKey) {
        call(() -> redis.delete(key(userId, idempotencyKey)));
    }

    private static <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new IdempotencyStoreUnavailableException(e);
        }
    }

    private static String key(String userId, String idempotencyKey) {
        return "idem:" + userId + ":" + idempotencyKey;
    }

    private static String toJson(IdempotencyRecord record) {
        try {
            return JSON.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IdempotencyRecord fromJson(String json) {
        try {
            return JSON.readValue(json, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt idempotency record", e);
        }
    }
}

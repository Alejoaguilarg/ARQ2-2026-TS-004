package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.model.security.gateways.CounterStore;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

// Counters without expiry: every test runs inside one window.
final class InMemoryCounterStore implements CounterStore {

    final Map<String, Long> values = new HashMap<>();
    boolean down;

    @Override
    public long increment(String key, Duration window) {
        check();
        return values.merge(key, 1L, Long::sum);
    }

    @Override
    public Duration timeToLive(String key) {
        check();
        return Duration.ofMillis(41_200);
    }

    @Override
    public boolean exists(String key) {
        check();
        return values.containsKey(key);
    }

    @Override
    public void set(String key, Duration ttl) {
        check();
        values.put(key, 1L);
    }

    @Override
    public void delete(String key) {
        check();
        values.remove(key);
    }

    private void check() {
        if (down) {
            throw new SecurityStoreUnavailableException(new IllegalStateException("redis down"));
        }
    }
}

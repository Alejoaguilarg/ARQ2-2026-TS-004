package co.com.finova.gateway.model.security.gateways;

import java.time.Duration;

// Every method throws SecurityStoreUnavailableException when the store cannot be reached.
public interface CounterStore {

    // Atomically increments the counter; the first increment starts its window.
    long increment(String key, Duration window);

    Duration timeToLive(String key);

    boolean exists(String key);

    void set(String key, Duration ttl);

    void delete(String key);
}

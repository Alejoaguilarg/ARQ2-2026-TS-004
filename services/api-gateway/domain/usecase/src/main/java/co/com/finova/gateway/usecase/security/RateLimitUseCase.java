package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.gateways.CounterStore;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
public class RateLimitUseCase {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final CounterStore counters;

    // spec.md §3.1.2: fixed one-minute window on rl:{scope}:{subject}. Returns the Retry-After when over the limit.
    public Optional<Duration> check(String scope, String subject, int limitPerMinute) {
        String key = "rl:" + scope + ":" + subject;
        if (counters.increment(key, WINDOW) <= limitPerMinute) {
            return Optional.empty();
        }
        return Optional.of(counters.timeToLive(key));
    }
}

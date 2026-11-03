package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.gateways.CounterStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitUseCaseTest {

    private final CounterStore counters = mock(CounterStore.class);
    private final RateLimitUseCase rateLimit = new RateLimitUseCase(counters);

    @Test
    void requestAtTheLimitIsAllowed() {
        when(counters.increment("rl:transfers:u1", Duration.ofMinutes(1))).thenReturn(30L);

        assertThat(rateLimit.check("transfers", "u1", 30)).isEmpty();
    }

    @Test
    void requestOverTheLimitGetsTheRemainingWindowAsRetryAfter() {
        when(counters.increment("rl:transfers:u1", Duration.ofMinutes(1))).thenReturn(31L);
        when(counters.timeToLive("rl:transfers:u1")).thenReturn(Duration.ofSeconds(42));

        assertThat(rateLimit.check("transfers", "u1", 30)).contains(Duration.ofSeconds(42));
    }
}

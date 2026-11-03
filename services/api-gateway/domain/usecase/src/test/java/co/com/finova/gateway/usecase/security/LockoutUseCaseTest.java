package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.gateways.CounterStore;
import co.com.finova.gateway.usecase.security.LockoutUseCase.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LockoutUseCaseTest {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

    private final CounterStore counters = mock(CounterStore.class);
    private final LockoutUseCase lockout = new LockoutUseCase(counters);

    @Test
    void fourthFailureDoesNotLock() {
        when(counters.increment("fail:login:ana@example.com", FIFTEEN_MINUTES)).thenReturn(4L);

        lockout.failed(Kind.LOGIN, "ana@example.com");

        verify(counters, never()).set(anyString(), any());
    }

    @Test
    void fifthFailedLoginLocksTheAccountFor15Minutes() {
        when(counters.increment("fail:login:ana@example.com", FIFTEEN_MINUTES)).thenReturn(5L);

        lockout.failed(Kind.LOGIN, "ana@example.com");

        verify(counters).set("lock:login:ana@example.com", FIFTEEN_MINUTES);
    }

    @Test
    void fifthFailedStepUpLocksStepUp() {
        when(counters.increment("fail:stepup:u1", FIFTEEN_MINUTES)).thenReturn(5L);

        lockout.failed(Kind.STEP_UP, "u1");

        verify(counters).set("lock:stepup:u1", FIFTEEN_MINUTES);
    }

    @Test
    void successClearsTheFailureCounter() {
        lockout.succeeded(Kind.LOGIN, "ana@example.com");

        verify(counters).delete("fail:login:ana@example.com");
    }

    @Test
    void lockedWhileTheLockKeyExists() {
        when(counters.exists("lock:stepup:u1")).thenReturn(true);

        assertThat(lockout.isLocked(Kind.STEP_UP, "u1")).isTrue();
        assertThat(lockout.isLocked(Kind.LOGIN, "u1")).isFalse();
    }
}

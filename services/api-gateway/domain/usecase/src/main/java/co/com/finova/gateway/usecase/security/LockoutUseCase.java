package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.gateways.CounterStore;
import lombok.RequiredArgsConstructor;

import java.time.Duration;

// spec.md §3.1.2 lockout counting: 5 failures in 15 minutes lock the route for 15 minutes.
@RequiredArgsConstructor
public class LockoutUseCase {

    public enum Kind {
        LOGIN("login"), STEP_UP("stepup");

        private final String keyPart;

        Kind(String keyPart) {
            this.keyPart = keyPart;
        }
    }

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final CounterStore counters;

    public boolean isLocked(Kind kind, String subject) {
        return counters.exists(lockKey(kind, subject));
    }

    public void failed(Kind kind, String subject) {
        if (counters.increment(failKey(kind, subject), WINDOW) >= MAX_FAILURES) {
            counters.set(lockKey(kind, subject), WINDOW);
        }
    }

    public void succeeded(Kind kind, String subject) {
        counters.delete(failKey(kind, subject));
    }

    private static String failKey(Kind kind, String subject) {
        return "fail:" + kind.keyPart + ":" + subject;
    }

    private static String lockKey(Kind kind, String subject) {
        return "lock:" + kind.keyPart + ":" + subject;
    }
}

package co.com.finova.wallet.model.outbox;

import co.com.finova.wallet.model.common.ContractViolationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pending item 28: a row that keeps failing waits longer each time, up to a cap, and is never dropped. */
class OutboxRetryBackoffTest {

    private final OutboxRetryBackoff backoff = new OutboxRetryBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5));

    @Test
    void theDelayDoublesAfterEachFailedAttempt() {
        assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.delayAfter(9)).isEqualTo(Duration.ofSeconds(256));
    }

    @Test
    void theDelayNeverExceedsTheMaximum() {
        assertThat(backoff.delayAfter(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(backoff.delayAfter(64)).isEqualTo(Duration.ofMinutes(5));
        assertThat(backoff.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsInvalidSettingsAndAttempts() {
        assertThatThrownBy(() -> new OutboxRetryBackoff(null, Duration.ofMinutes(5)))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> new OutboxRetryBackoff(Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> new OutboxRetryBackoff(Duration.ofSeconds(1), null))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> new OutboxRetryBackoff(Duration.ofMinutes(5), Duration.ofSeconds(1)))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> backoff.delayAfter(0)).isInstanceOf(ContractViolationException.class);
    }
}

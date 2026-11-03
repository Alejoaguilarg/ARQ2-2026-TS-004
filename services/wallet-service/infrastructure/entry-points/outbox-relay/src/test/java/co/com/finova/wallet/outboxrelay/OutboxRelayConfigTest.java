package co.com.finova.wallet.outboxrelay;

import co.com.finova.wallet.model.outbox.OutboxRetryBackoff;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayConfigTest {

    @Test
    void buildsTheRetryBackoffFromTheConfiguredDelays() {
        OutboxRetryBackoff backoff = new OutboxRelayConfig()
                .outboxRetryBackoff(Duration.ofSeconds(2), Duration.ofMinutes(1));

        assertThat(backoff).isEqualTo(new OutboxRetryBackoff(Duration.ofSeconds(2), Duration.ofMinutes(1)));
    }
}

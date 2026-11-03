package co.com.finova.transfer.outboxrelay;

import co.com.finova.transfer.model.outbox.OutboxRetryBackoff;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Retry delays of the outbox relay (audit finding H1). Not conditional: RelayOutboxUseCase is registered even
 * when the relay scheduler is off. Override with ADAPTERS_OUTBOX_RETRY_INITIAL_DELAY and
 * ADAPTERS_OUTBOX_RETRY_MAX_DELAY.
 */
@Configuration
public class OutboxRelayConfig {

    @Bean
    public OutboxRetryBackoff outboxRetryBackoff(
            @Value("${adapters.outbox.retry-initial-delay:1s}") Duration initialDelay,
            @Value("${adapters.outbox.retry-max-delay:5m}") Duration maxDelay) {
        return new OutboxRetryBackoff(initialDelay, maxDelay);
    }
}

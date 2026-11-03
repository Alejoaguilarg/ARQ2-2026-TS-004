package co.com.finova.wallet.outboxrelay;

import co.com.finova.wallet.usecase.relayoutbox.RelayOutboxUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the outbox relay (spec §1.4, ADR 002; decision S3): one batch every relay-interval, waiting for the
 * previous run to end (fixed delay). Turned off with adapters.outbox.relay-enabled=false.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "adapters.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final RelayOutboxUseCase relay;
    private final int batchSize;

    public OutboxRelayScheduler(RelayOutboxUseCase relay,
                                @Value("${adapters.outbox.batch-size:100}") int batchSize) {
        this.relay = relay;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${adapters.outbox.relay-interval:100ms}")
    public void relayPending() {
        try {
            relay.relay(batchSize);
        } catch (RuntimeException e) {
            // For example wallet_db unavailable: the rows stay pending and the next run tries again.
            LOG.warn("Outbox relay run failed, retrying on the next run: {}", e.getMessage(), e);
        }
    }
}

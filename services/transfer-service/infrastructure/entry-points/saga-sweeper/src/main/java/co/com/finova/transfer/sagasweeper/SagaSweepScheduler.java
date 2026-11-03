package co.com.finova.transfer.sagasweeper;

import co.com.finova.transfer.usecase.sweeptimeouts.SweepTimeoutsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the timeout sweeper (spec §4.6 rule 8): every sweep-interval (2 s), waiting for the previous run to end.
 * Turned off with adapters.saga.sweeper-enabled=false.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "adapters.saga.sweeper-enabled", havingValue = "true", matchIfMissing = true)
public class SagaSweepScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SagaSweepScheduler.class);

    private final SweepTimeoutsUseCase sweeper;
    private final int batchSize;

    public SagaSweepScheduler(SweepTimeoutsUseCase sweeper,
                              @Value("${adapters.saga.sweep-batch-size:100}") int batchSize) {
        this.sweeper = sweeper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${adapters.saga.sweep-interval:2s}")
    public void sweep() {
        try {
            sweeper.sweep(batchSize);
        } catch (RuntimeException e) {
            // For example transfer_db unavailable: the transfers stay due and the next run tries again.
            LOG.warn("Saga sweep failed, retrying on the next run: {}", e.getMessage(), e);
        }
    }
}

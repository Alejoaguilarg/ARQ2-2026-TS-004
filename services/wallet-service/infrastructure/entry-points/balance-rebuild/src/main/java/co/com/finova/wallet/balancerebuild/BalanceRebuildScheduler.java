package co.com.finova.wallet.balancerebuild;

import co.com.finova.wallet.usecase.rebuildbalancecache.RebuildBalanceCacheUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Triggers the balance cache rebuild job (spec §4.5, ADR 004; decision S6): once at startup and then every
 * interval, waiting for the previous run to end (fixed delay). Turned off with adapters.balance-rebuild.enabled=false.
 * A job has no incoming correlationId, so each run gets a new one in the MDC (spec §1.4).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "adapters.balance-rebuild.enabled", havingValue = "true", matchIfMissing = true)
public class BalanceRebuildScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceRebuildScheduler.class);
    private static final String CORRELATION_ID = "correlationId";

    private final RebuildBalanceCacheUseCase rebuild;
    private final int pageSize;

    public BalanceRebuildScheduler(RebuildBalanceCacheUseCase rebuild,
                                   @Value("${adapters.balance-rebuild.page-size:500}") int pageSize) {
        this.rebuild = rebuild;
        this.pageSize = pageSize;
    }

    @Scheduled(initialDelayString = "${adapters.balance-rebuild.initial-delay:0s}",
            fixedDelayString = "${adapters.balance-rebuild.interval:5m}")
    public void rebuild() {
        MDC.put(CORRELATION_ID, UUID.randomUUID().toString());
        try {
            RebuildBalanceCacheUseCase.Result result = rebuild.rebuild(pageSize);
            LOG.info("Balance cache rebuild {}: written={} skipped={}",
                    result.complete() ? "finished" : "stopped early", result.written(), result.skipped());
        } catch (RuntimeException e) {
            // For example wallet_db unavailable: the cache stays as it is and the next run tries again.
            LOG.warn("Balance cache rebuild failed, retrying on the next run: {}", e.getMessage(), e);
        } finally {
            MDC.remove(CORRELATION_ID);
        }
    }
}

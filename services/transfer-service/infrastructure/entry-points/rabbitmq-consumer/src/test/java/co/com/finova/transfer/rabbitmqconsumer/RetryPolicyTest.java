package co.com.finova.transfer.rabbitmqconsumer;

import co.com.finova.transfer.model.common.ContractViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retry rules (spec §4.2, §4.5): technical errors are retried up to
 * the limit counted from the x-death "rejected" entry of the main queue; invalid messages go to the DLQ at once.
 */
class RetryPolicyTest {

    private static final String QUEUE = "transfer.wallet-events.q";
    private static final String RETRY_QUEUE = "transfer.wallet-events.q.retry";

    private final RetryPolicy policy = new RetryPolicy(3);
    private final RuntimeException technical = new IllegalStateException("database unavailable");

    @Test
    void retriesATechnicalErrorOnTheFirstDelivery() {
        assertThat(policy.decide(technical, QUEUE, List.of())).isEqualTo(RetryPolicy.Decision.RETRY);
        assertThat(policy.decide(technical, QUEUE, null)).isEqualTo(RetryPolicy.Decision.RETRY);
    }

    @Test
    void retriesWhileTheMainQueueRejectedFewerTimesThanTheLimit() {
        assertThat(policy.decide(technical, QUEUE, xDeath(rejected(QUEUE, 2L), expired(RETRY_QUEUE, 2L))))
                .isEqualTo(RetryPolicy.Decision.RETRY);
    }

    @Test
    void deadLettersOnTheFourthDeliveryAfterThreeRetries() {
        assertThat(policy.decide(technical, QUEUE, xDeath(rejected(QUEUE, 3L), expired(RETRY_QUEUE, 3L))))
                .isEqualTo(RetryPolicy.Decision.DEAD_LETTER);
    }

    @Test
    void ignoresTheExpiredEntryOfTheRetryQueue() {
        assertThat(policy.decide(technical, QUEUE, xDeath(rejected(QUEUE, 1L), expired(RETRY_QUEUE, 9L))))
                .isEqualTo(RetryPolicy.Decision.RETRY);
    }

    @Test
    void ignoresEntriesOfOtherQueues() {
        assertThat(policy.decide(technical, QUEUE, xDeath(rejected("audit.attempts.q", 9L))))
                .isEqualTo(RetryPolicy.Decision.RETRY);
    }

    @Test
    void acceptsAnIntegerCount() {
        assertThat(policy.decide(technical, QUEUE, xDeath(rejected(QUEUE, 3))))
                .isEqualTo(RetryPolicy.Decision.DEAD_LETTER);
    }

    @Test
    void usesTheConfiguredLimit() {
        RetryPolicy auditLimit = new RetryPolicy(5);

        assertThat(auditLimit.decide(technical, QUEUE, xDeath(rejected(QUEUE, 4L))))
                .isEqualTo(RetryPolicy.Decision.RETRY);
        assertThat(auditLimit.decide(technical, QUEUE, xDeath(rejected(QUEUE, 5L))))
                .isEqualTo(RetryPolicy.Decision.DEAD_LETTER);
    }

    @Test
    void retriesADatabaseOutage() {
        assertThat(policy.decide(new IllegalStateException("transfer_db down"), QUEUE, List.of()))
                .isEqualTo(RetryPolicy.Decision.RETRY);
    }

    @Test
    void deadLettersAContractViolationAtOnce() {
        assertThat(policy.decide(new ContractViolationException("amount must be > 0"), QUEUE, List.of()))
                .isEqualTo(RetryPolicy.Decision.DEAD_LETTER);
    }

    @Test
    void looksForAnInvalidMessageInTheCauseChain() {
        RuntimeException wrapped = new IllegalStateException("listener failed",
                new ContractViolationException("unknown message type: PayWallet"));

        assertThat(policy.decide(wrapped, QUEUE, List.of())).isEqualTo(RetryPolicy.Decision.DEAD_LETTER);
    }

    @Test
    void rejectsANonPositiveLimit() {
        assertThatThrownBy(() -> new RetryPolicy(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @SafeVarargs
    private static List<Map<String, ?>> xDeath(Map<String, ?>... entries) {
        return List.of(entries);
    }

    private static Map<String, ?> rejected(String queue, Object count) {
        return Map.of("queue", queue, "reason", "rejected", "count", count, "exchange", "");
    }

    private static Map<String, ?> expired(String queue, Object count) {
        return Map.of("queue", queue, "reason", "expired", "count", count, "exchange", "");
    }
}

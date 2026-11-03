package co.com.finova.wallet.rabbitmqconsumer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerConfigTest {

    private final ListenerConfig config = new ListenerConfig();

    @Test
    void retryPolicyUsesTheConfiguredLimit() {
        RetryPolicy policy = config.retryPolicy(2);
        List<Map<String, ?>> twoRejections = List.of(
                Map.of("queue", "wallet.commands.q", "reason", "rejected", "count", 2L));

        assertThat(policy.decide(new IllegalStateException("down"), "wallet.commands.q", twoRejections))
                .isEqualTo(RetryPolicy.Decision.DEAD_LETTER);
    }

    @Test
    void deliveryOutcomeHandlerIsBuiltFromThePolicyAndTheDlqSender() {
        assertThat(config.deliveryOutcomeHandler(config.retryPolicy(3), (message, queue) -> { })).isNotNull();
    }
}

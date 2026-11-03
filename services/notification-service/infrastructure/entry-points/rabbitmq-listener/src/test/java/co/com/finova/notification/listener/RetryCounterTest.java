package co.com.finova.notification.listener;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetryCounterTest {

    private static final String QUEUE = "notification.transfer-events.q";

    @Test
    void firstDeliveryHasNoRejections() {
        assertThat(RetryCounter.previousRejections(new MessageProperties(), QUEUE)).isZero();
    }

    @Test
    void readsTheRejectionsOfTheMainQueue() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-death", List.of(
                Map.of("queue", QUEUE + ".retry", "reason", "expired", "count", 2L),
                Map.of("queue", QUEUE, "reason", "rejected", "count", 2L)));

        assertThat(RetryCounter.previousRejections(properties, QUEUE)).isEqualTo(2);
    }

    @Test
    void ignoresOtherQueuesAndReasons() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-death", List.of(
                Map.of("queue", "other.q", "reason", "rejected", "count", 9L),
                Map.of("queue", QUEUE, "reason", "maxlen", "count", 9L)));

        assertThat(RetryCounter.previousRejections(properties, QUEUE)).isZero();
    }
}

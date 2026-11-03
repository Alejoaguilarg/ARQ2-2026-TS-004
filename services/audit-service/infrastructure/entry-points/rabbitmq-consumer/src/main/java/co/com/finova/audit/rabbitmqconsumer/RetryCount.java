package co.com.finova.audit.rabbitmqconsumer;

import org.springframework.amqp.core.Message;

import java.util.List;
import java.util.Map;

/**
 * How many times a message was already rejected by this consumer (spec §4.2): RabbitMQ keeps one x-death
 * entry per (queue, reason) with a count, and every rejection sends the message through the .retry queue.
 */
final class RetryCount {

    private static final String X_DEATH_HEADER = "x-death";
    private static final String REJECTED = "rejected";

    private RetryCount() {
    }

    static long of(Message message, String queue) {
        Object header = message.getMessageProperties().getHeaders().get(X_DEATH_HEADER);
        if (!(header instanceof List<?> deaths)) {
            return 0;
        }
        for (Object death : deaths) {
            if (death instanceof Map<?, ?> entry
                    && queue.equals(String.valueOf(entry.get("queue")))
                    && REJECTED.equals(String.valueOf(entry.get("reason")))
                    && entry.get("count") instanceof Number count) {
                return count.longValue();
            }
        }
        return 0;
    }
}

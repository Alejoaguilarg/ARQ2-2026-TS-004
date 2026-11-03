package co.com.finova.notification.listener;

import org.springframework.amqp.core.MessageProperties;

import java.util.List;
import java.util.Map;

/**
 * Counts how many times a message was already rejected from a queue, using the x-death header that
 * RabbitMQ adds each time it dead-letters the message (queue → .retry → queue, spec §4.2).
 *
 * <p>x-death is a list with one entry per (queue, reason); the entry for the main queue with reason
 * "rejected" holds the number of failed attempts so far.</p>
 */
final class RetryCounter {

    private static final String X_DEATH = "x-death";

    private RetryCounter() {
    }

    static long previousRejections(MessageProperties properties, String queue) {
        Object deaths = properties.getXDeathHeader();
        if (deaths == null) {
            deaths = properties.getHeader(X_DEATH);
        }
        if (!(deaths instanceof List<?> entries)) {
            return 0;
        }
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> death
                    && queue.equals(String.valueOf(death.get("queue")))
                    && "rejected".equals(String.valueOf(death.get("reason")))
                    && death.get("count") instanceof Number count) {
                return count.longValue();
            }
        }
        return 0;
    }
}

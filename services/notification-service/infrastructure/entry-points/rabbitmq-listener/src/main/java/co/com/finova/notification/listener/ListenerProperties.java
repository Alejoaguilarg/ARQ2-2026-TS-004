package co.com.finova.notification.listener;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Queue topology used by the consumer (spec §4.2 and §4.5). The queues are declared by
 * rabbitmq-init (infra/rabbitmq/definitions.json); this service never declares them.
 *
 * @param queue           main queue (notification.transfer-events.q)
 * @param deadLetterQueue DLQ where exhausted or unprocessable messages are moved
 * @param maxRetries      retries after the first attempt before moving a message to the DLQ (spec: 3)
 * @param confirmTimeout  how long to wait for the broker to confirm a message moved to the DLQ
 */
@ConfigurationProperties(prefix = "adapters.rabbitmq")
public record ListenerProperties(String queue, String deadLetterQueue, int maxRetries, Duration confirmTimeout) {
}

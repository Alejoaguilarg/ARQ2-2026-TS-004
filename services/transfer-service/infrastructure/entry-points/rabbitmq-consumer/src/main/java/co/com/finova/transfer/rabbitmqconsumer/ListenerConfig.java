package co.com.finova.transfer.rabbitmqconsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retry and DLQ handling of the listeners (spec §4.2, §4.5). The listener container itself comes from Spring
 * Boot, configured in application.yaml (manual ack, prefetch). No Queue, Exchange or Binding beans: the
 * topology belongs to the broker (infra/rabbitmq/definitions.json).
 */
@Configuration
public class ListenerConfig {

    @Bean
    public RetryPolicy retryPolicy(@Value("${adapters.rabbitmq.max-retries:3}") int maxRetries) {
        return new RetryPolicy(maxRetries);
    }

    @Bean
    public DeliveryOutcomeHandler deliveryOutcomeHandler(RetryPolicy retryPolicy, DeadLetterSender deadLetters) {
        return new DeliveryOutcomeHandler(retryPolicy, deadLetters);
    }
}

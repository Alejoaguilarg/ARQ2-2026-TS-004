package co.com.finova.wallet.rabbitmqconsumer;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes to the DLQ through the default exchange ("" with the queue name as routing key), so nothing has
 * to be declared: the .dlq queues already exist (infra/rabbitmq/definitions.json). Waits for the publisher
 * confirm and fails when the queue does not exist (mandatory + returned message).
 */
@Component
public class RabbitDeadLetterSender implements DeadLetterSender {

    private static final String DEFAULT_EXCHANGE = "";

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public RabbitDeadLetterSender(RabbitTemplate rabbitTemplate,
                                  @Value("${adapters.rabbitmq.confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void send(Message message, String deadLetterQueue) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send(DEFAULT_EXCHANGE, deadLetterQueue, message, correlation);
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the confirm of " + deadLetterQueue, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No broker confirm for " + deadLetterQueue, e);
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("Broker nacked " + deadLetterQueue + ": " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Unroutable message: queue " + deadLetterQueue + " does not exist");
        }
    }
}

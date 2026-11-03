package co.com.finova.audit.rabbitmqconsumer;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
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
 * Moves a message to audit.attempts.q.dlq (spec §4.2): publishes a copy through the default exchange and
 * waits for the broker confirm, so the caller acks the original only once the copy is safe. The copy keeps
 * the body, message-id, correlation-id and headers, plus the reason and the number of retries.
 */
@Component
public class DeadLetterPublisher {

    public static final String DEAD_LETTER_QUEUE = "audit.attempts.q.dlq";
    static final String REASON_HEADER = "x-audit-dead-letter-reason";
    static final String RETRIES_HEADER = "x-audit-retries";

    private static final String DEFAULT_EXCHANGE = "";
    private static final String X_DEATH_HEADER = "x-death";
    private static final int MAX_REASON_LENGTH = 1_000;

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public DeadLetterPublisher(RabbitTemplate rabbitTemplate,
                               @Value("${adapters.rabbitmq.confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    public void publish(Message original, long retries, String reason) {
        MessageProperties received = original.getMessageProperties();
        MessageProperties properties = new MessageProperties();
        received.getHeaders().forEach((name, value) -> {
            if (!X_DEATH_HEADER.equals(name)) {
                properties.setHeader(name, value);
            }
        });
        properties.setHeader(REASON_HEADER, truncate(reason));
        properties.setHeader(RETRIES_HEADER, retries);
        properties.setMessageId(received.getMessageId());
        properties.setCorrelationId(received.getCorrelationId());
        if (received.getContentType() != null) {
            properties.setContentType(received.getContentType());
        }
        if (received.getContentEncoding() != null) {
            properties.setContentEncoding(received.getContentEncoding());
        }
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        String id = received.getMessageId() != null ? received.getMessageId() : UUID.randomUUID().toString();
        CorrelationData correlation = new CorrelationData(id);
        rabbitTemplate.send(DEFAULT_EXCHANGE, DEAD_LETTER_QUEUE, new Message(original.getBody(), properties),
                correlation);
        awaitConfirm(id, correlation);
    }

    private void awaitConfirm(String id, CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadLetterPublishException("interrupted waiting for the DLQ confirm of " + id, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeadLetterPublishException("no broker confirm for the DLQ copy of " + id, e);
        }
        if (!confirm.isAck()) {
            throw new DeadLetterPublishException("broker nacked the DLQ copy of " + id + ": " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new DeadLetterPublishException("DLQ copy of " + id + " was unroutable to " + DEAD_LETTER_QUEUE);
        }
    }

    private static String truncate(String reason) {
        String text = String.valueOf(reason);
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }
}

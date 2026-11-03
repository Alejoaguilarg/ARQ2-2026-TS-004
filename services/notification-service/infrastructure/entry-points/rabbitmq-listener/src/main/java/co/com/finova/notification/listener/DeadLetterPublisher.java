package co.com.finova.notification.listener;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Moves a message to the DLQ through the default exchange (routing key = DLQ name) and waits for the
 * publisher confirm, so the original is only acked once the copy is safe in the DLQ.
 */
public class DeadLetterPublisher {

    static final String ERROR_HEADER = "x-notification-error";
    static final String ORIGINAL_QUEUE_HEADER = "x-original-queue";
    private static final int MAX_ERROR_LENGTH = 500;

    private final RabbitTemplate rabbitTemplate;
    private final ListenerProperties properties;

    public DeadLetterPublisher(RabbitTemplate rabbitTemplate, ListenerProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(Message original, String error) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties target = new MessageProperties();
        target.setMessageId(source.getMessageId());
        target.setCorrelationId(source.getCorrelationId());
        target.setType(source.getType());
        target.setContentType(source.getContentType());
        target.setContentEncoding(source.getContentEncoding());
        target.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        source.getHeaders().forEach((name, value) -> {
            if (!name.startsWith("x-death") && !name.startsWith("x-first-death") && !name.startsWith("x-last-death")) {
                target.setHeader(name, value);
            }
        });
        target.setHeader(ERROR_HEADER, truncate(error));
        target.setHeader(ORIGINAL_QUEUE_HEADER, properties.queue());

        String id = source.getMessageId() != null ? source.getMessageId() : UUID.randomUUID().toString();
        CorrelationData correlation = new CorrelationData(id);
        rabbitTemplate.send("", properties.deadLetterQueue(), new Message(original.getBody(), target), correlation);
        awaitConfirm(correlation);
    }

    private void awaitConfirm(CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while moving message to " + properties.deadLetterQueue(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No broker confirm moving message to " + properties.deadLetterQueue(), e);
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("Broker refused message for " + properties.deadLetterQueue()
                    + ": " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Queue " + properties.deadLetterQueue() + " does not exist");
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return "";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}

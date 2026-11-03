package co.com.finova.notification.listener;

import co.com.finova.notification.model.exceptions.RecipientUnreachableException;
import co.com.finova.notification.model.notification.TransferOutcome;
import co.com.finova.notification.usecase.notifytransferoutcome.NotifyTransferOutcomeUseCase;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Driving adapter: consumes notification.transfer-events.q (spec §4.2) and calls the use case.
 *
 * <ul>
 *   <li>Success → ack.</li>
 *   <li>Technical failure (provider down, database error) → reject without requeue: RabbitMQ moves
 *       the message to the .retry queue, which returns it after 5 s. After {@code maxRetries}
 *       retries the message is moved to the DLQ.</li>
 *   <li>Message that breaks the contract, or user without contact data → straight to the DLQ,
 *       because retrying will not fix it.</li>
 * </ul>
 */
@Component
public class TransferEventsListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsListener.class);
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TRANSFER_ID = "transferId";

    private final NotifyTransferOutcomeUseCase useCase;
    private final TransferEventMapper mapper;
    private final DeadLetterPublisher deadLetterPublisher;
    private final ListenerProperties properties;

    public TransferEventsListener(NotifyTransferOutcomeUseCase useCase, TransferEventMapper mapper,
                                  DeadLetterPublisher deadLetterPublisher, ListenerProperties properties) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.deadLetterPublisher = deadLetterPublisher;
        this.properties = properties;
    }

    @RabbitListener(queues = "${adapters.rabbitmq.queue}", ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        MessageProperties messageProperties = message.getMessageProperties();
        putInMdc(MDC_CORRELATION_ID, messageProperties.getCorrelationId());
        try {
            process(message, channel);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_TRANSFER_ID);
        }
    }

    private void process(Message message, Channel channel) throws IOException {
        MessageProperties messageProperties = message.getMessageProperties();
        long deliveryTag = messageProperties.getDeliveryTag();
        String messageId = messageProperties.getMessageId();

        TransferOutcome outcome;
        try {
            outcome = mapper.toOutcome(message.getBody());
        } catch (InvalidMessageException e) {
            log.error("Message {} does not follow the contract: {}", messageId, e.getMessage());
            moveToDeadLetter(message, channel, deliveryTag, "INVALID_MESSAGE: " + e.getMessage());
            return;
        }
        putInMdc(MDC_CORRELATION_ID, outcome.correlationId());
        putInMdc(MDC_TRANSFER_ID, outcome.transferId().toString());

        try {
            useCase.execute(outcome);
            channel.basicAck(deliveryTag, false);
            log.info("{} processed (message {})", outcome.type().messageType(), messageId);
        } catch (RecipientUnreachableException e) {
            log.error("{}; moving message {} to the DLQ", e.getMessage(), messageId);
            moveToDeadLetter(message, channel, deliveryTag, "RECIPIENT_UNREACHABLE: " + e.getMessage());
        } catch (RuntimeException e) {
            long previousRejections = RetryCounter.previousRejections(messageProperties, properties.queue());
            if (previousRejections >= properties.maxRetries()) {
                log.error("Retries exhausted for message {}: {}", messageId, e.getMessage());
                moveToDeadLetter(message, channel, deliveryTag, "RETRIES_EXHAUSTED: " + e.getMessage());
            } else {
                log.warn("Attempt {} of {} failed for message {}, retrying in 5 s: {}",
                        previousRejections + 1, properties.maxRetries() + 1, messageId, e.getMessage());
                channel.basicReject(deliveryTag, false);
            }
        }
    }

    private void moveToDeadLetter(Message message, Channel channel, long deliveryTag, String error)
            throws IOException {
        try {
            deadLetterPublisher.publish(message, error);
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            log.error("Could not move message {} to the DLQ, sending it to the retry queue: {}",
                    message.getMessageProperties().getMessageId(), e.getMessage());
            channel.basicReject(deliveryTag, false);
        }
    }

    private static void putInMdc(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}

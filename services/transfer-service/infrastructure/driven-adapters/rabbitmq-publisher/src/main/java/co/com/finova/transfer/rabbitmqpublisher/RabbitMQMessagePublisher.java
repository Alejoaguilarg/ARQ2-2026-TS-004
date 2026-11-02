package co.com.finova.transfer.rabbitmqpublisher;

import co.com.finova.transfer.model.message.MessagePublishException;
import co.com.finova.transfer.model.message.gateways.MessagePublisher;
import co.com.finova.transfer.model.message.envelope.MessageEnvelope;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RabbitMQMessagePublisher implements MessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public RabbitMQMessagePublisher(RabbitTemplate rabbitTemplate,
                                    @Value("${adapters.rabbitmq.confirm-timeout:5s}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(MessageEnvelope envelope) {
        CorrelationData correlation = new CorrelationData(envelope.getMessageId());
        Message message = toAmqp(envelope);
        rabbitTemplate.send(envelope.getExchange(), envelope.getRoutingKey(), message, correlation);
        awaitConfirm(envelope, correlation);
    }

    private void awaitConfirm(MessageEnvelope envelope, CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("Interrupted waiting for confirm of " + envelope.getMessageId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new MessagePublishException("No broker confirm for " + envelope.getMessageId(), e);
        }
        if (!confirm.isAck()) {
            throw new MessagePublishException("Broker nacked " + envelope.getMessageId() + ": " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new MessagePublishException("Unroutable " + envelope.getMessageId() + " to "
                    + envelope.getExchange() + "/" + envelope.getRoutingKey());
        }
    }

    private Message toAmqp(MessageEnvelope envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(envelope.getMessageId());
        properties.setCorrelationId(envelope.getCorrelationId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        String payload = envelope.getPayload().toString();
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }
}

package co.com.finova.wallet.rabbitmqpublisher;

import co.com.finova.wallet.model.message.MessagePublishException;
import co.com.finova.wallet.model.message.OutboundMessage;
import co.com.finova.wallet.model.message.gateways.MessagePublisher;
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
    public void publish(OutboundMessage message) {
        CorrelationData correlation = new CorrelationData(message.messageId());
        rabbitTemplate.send(message.exchange(), message.routingKey(), toAmqp(message), correlation);
        awaitConfirm(message, correlation);
    }

    private void awaitConfirm(OutboundMessage message, CorrelationData correlation) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("Interrupted waiting for confirm of " + message.messageId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new MessagePublishException("No broker confirm for " + message.messageId(), e);
        }
        if (!confirm.isAck()) {
            throw new MessagePublishException("Broker nacked " + message.messageId() + ": " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new MessagePublishException("Unroutable " + message.messageId() + " to "
                    + message.exchange() + "/" + message.routingKey());
        }
    }

    private static Message toAmqp(OutboundMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(message.messageId());
        properties.setCorrelationId(message.correlationId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(message.payload().getBytes(StandardCharsets.UTF_8), properties);
    }
}

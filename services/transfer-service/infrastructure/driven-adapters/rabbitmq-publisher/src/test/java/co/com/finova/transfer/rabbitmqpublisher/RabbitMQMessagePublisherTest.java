package co.com.finova.transfer.rabbitmqpublisher;

import co.com.finova.transfer.model.message.MessagePublishException;
import co.com.finova.transfer.model.message.envelope.MessageEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RabbitMQMessagePublisherTest {

    private static final MessageEnvelope MESSAGE = new MessageEnvelope(
            "msg-1", "transfer.completed", "corr-1", "t-1", "transfer-service",
            "transfer.events", "transfer.completed", Instant.now(), "{\"transferId\":\"t-1\"}");

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitMQMessagePublisher publisher =
            new RabbitMQMessagePublisher(rabbitTemplate, Duration.ofMillis(200));

    @Test
    void publishesPersistentMessageWithIdsAndWaitsForAck() {
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publish(MESSAGE);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("transfer.events"), eq("transfer.completed"), sent.capture(), any(CorrelationData.class));
        MessageProperties properties = sent.getValue().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo("msg-1");
        assertThat(properties.getCorrelationId()).isEqualTo("corr-1");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(MESSAGE.getPayload().toString());
    }

    @Test
    void failsWhenBrokerNacks() {
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "disk alarm")));

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(MessagePublishException.class)
                .hasMessageContaining("disk alarm");
    }

    @Test
    void failsWhenMessageIsUnroutable() {
        brokerAnswers(correlation -> {
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0]), 312, "NO_ROUTE", "transfer.events", "transfer.debited"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(MessagePublishException.class)
                .hasMessageContaining("Unroutable");
    }

    @Test
    void failsWhenNoConfirmArrivesInTime() {
        brokerAnswers(correlation -> { });

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(MessagePublishException.class)
                .hasMessageContaining("No broker confirm");
    }

    private void brokerAnswers(Consumer<CorrelationData> broker) {
        doAnswer(invocation -> {
            broker.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }
}

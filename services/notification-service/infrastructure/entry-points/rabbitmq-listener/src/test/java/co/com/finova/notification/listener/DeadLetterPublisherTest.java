package co.com.finova.notification.listener;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DeadLetterPublisherTest {

    private static final String QUEUE = "notification.transfer-events.q";
    private static final String DLQ = QUEUE + ".dlq";

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final DeadLetterPublisher publisher = new DeadLetterPublisher(rabbitTemplate,
            new ListenerProperties(QUEUE, DLQ, 3, Duration.ofSeconds(1)));

    @Test
    void publishesAPersistentCopyWithTheErrorThroughTheDefaultExchange() {
        brokerAnswers(true);

        publisher.publish(original(), "RETRIES_EXHAUSTED: provider unavailable");

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(DLQ), sent.capture(), any(CorrelationData.class));
        MessageProperties properties = sent.getValue().getMessageProperties();
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"type\":\"x\"}");
        assertThat(properties.getMessageId()).isEqualTo("msg-1");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((String) properties.getHeader(DeadLetterPublisher.ERROR_HEADER))
                .isEqualTo("RETRIES_EXHAUSTED: provider unavailable");
        assertThat((String) properties.getHeader(DeadLetterPublisher.ORIGINAL_QUEUE_HEADER)).isEqualTo(QUEUE);
        assertThat(properties.getHeaders()).doesNotContainKey("x-death");
    }

    @Test
    void failsWhenTheBrokerDoesNotConfirm() {
        brokerAnswers(false);

        assertThatIllegalStateException().isThrownBy(() -> publisher.publish(original(), "error"));
    }

    @Test
    void failsWhenTheDlqDoesNotExist() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            Message message = invocation.getArgument(2);
            correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "", DLQ));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq(""), eq(DLQ), any(Message.class), any(CorrelationData.class));

        assertThatIllegalStateException().isThrownBy(() -> publisher.publish(original(), "error"))
                .withMessageContaining("does not exist");
    }

    @Test
    void failsWhenTheBrokerDoesNotAnswerInTime() {
        DeadLetterPublisher impatient = new DeadLetterPublisher(rabbitTemplate,
                new ListenerProperties(QUEUE, DLQ, 3, Duration.ofMillis(50)));

        assertThatIllegalStateException().isThrownBy(() -> impatient.publish(original(), "error"))
                .withMessageContaining("No broker confirm");
    }

    @Test
    void truncatesLongErrorsAndAcceptsMessagesWithoutId() {
        brokerAnswers(true);
        Message withoutId = new Message("x".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        publisher.publish(withoutId, "e".repeat(800));
        publisher.publish(withoutId, null);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate, times(2)).send(eq(""), eq(DLQ), sent.capture(), any(CorrelationData.class));
        assertThat((String) sent.getAllValues().get(0).getMessageProperties()
                .getHeader(DeadLetterPublisher.ERROR_HEADER)).hasSize(500);
        assertThat((String) sent.getAllValues().get(1).getMessageProperties()
                .getHeader(DeadLetterPublisher.ERROR_HEADER)).isEmpty();
    }

    private void brokerAnswers(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(rabbitTemplate).send(eq(""), eq(DLQ), any(Message.class), any(CorrelationData.class));
    }

    private static Message original() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("msg-1");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("x-death", List.of(Map.of("queue", QUEUE, "reason", "rejected", "count", 3L)));
        return new Message("{\"type\":\"x\"}".getBytes(StandardCharsets.UTF_8), properties);
    }
}

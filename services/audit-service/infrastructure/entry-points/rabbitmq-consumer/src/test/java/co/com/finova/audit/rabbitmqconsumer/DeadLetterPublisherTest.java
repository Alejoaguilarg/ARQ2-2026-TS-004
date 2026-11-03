package co.com.finova.audit.rabbitmqconsumer;

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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Spec §4.2: the DLQ copy keeps the message and is only reported safe after a broker ack. */
class DeadLetterPublisherTest {

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final DeadLetterPublisher publisher = new DeadLetterPublisher(rabbit, Duration.ofMillis(200));

    @Test
    void copiesTheMessageToTheDlqWithReasonAndRetries() {
        broker(c -> c.getFuture().complete(new CorrelationData.Confirm(true, null)));
        MessageProperties props = new MessageProperties();
        props.setMessageId("m-1");
        props.setCorrelationId("c-1");
        props.setContentType("application/json");
        props.setContentEncoding("UTF-8");
        props.setHeader("traceparent", "00-abc");
        props.setHeader("x-death", List.of(Map.of("count", 5L)));

        publisher.publish(new Message("{}".getBytes(StandardCharsets.UTF_8), props), 5, "bad payload");

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(""), eq(DeadLetterPublisher.DEAD_LETTER_QUEUE), sent.capture(),
                any(CorrelationData.class));
        MessageProperties copy = sent.getValue().getMessageProperties();
        assertThat(sent.getValue().getBody()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(copy.getMessageId()).isEqualTo("m-1");
        assertThat(copy.getCorrelationId()).isEqualTo("c-1");
        assertThat(copy.getContentType()).isEqualTo("application/json");
        assertThat(copy.getContentEncoding()).isEqualTo("UTF-8");
        assertThat(copy.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(copy.getHeaders())
                .containsEntry("traceparent", "00-abc")
                .containsEntry(DeadLetterPublisher.REASON_HEADER, "bad payload")
                .containsEntry(DeadLetterPublisher.RETRIES_HEADER, 5L)
                .doesNotContainKey("x-death");
    }

    @Test
    void aLongReasonIsTruncatedAndAMessageWithoutIdStillGetsAConfirmId() {
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        broker(c -> c.getFuture().complete(new CorrelationData.Confirm(true, null)));

        publisher.publish(new Message(new byte[0], new MessageProperties()), 0, "x".repeat(5_000));

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(anyString(), anyString(), sent.capture(), correlation.capture());
        assertThat((String) sent.getValue().getMessageProperties().getHeader(DeadLetterPublisher.REASON_HEADER))
                .hasSize(1_000);
        assertThat(correlation.getValue().getId()).isNotBlank();
    }

    @Test
    void aNackFails() {
        broker(c -> c.getFuture().complete(new CorrelationData.Confirm(false, "queue full")));

        assertThatThrownBy(this::publishOne).isInstanceOf(DeadLetterPublishException.class)
                .hasMessageContaining("nacked").hasMessageContaining("queue full");
    }

    @Test
    void anUnroutableCopyFails() {
        broker(c -> {
            c.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "",
                    DeadLetterPublisher.DEAD_LETTER_QUEUE));
            c.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatThrownBy(this::publishOne).isInstanceOf(DeadLetterPublishException.class)
                .hasMessageContaining("unroutable");
    }

    @Test
    void noConfirmInTimeFails() {
        broker(c -> { });

        assertThatThrownBy(this::publishOne).isInstanceOf(DeadLetterPublishException.class)
                .hasMessageContaining("no broker confirm");
    }

    @Test
    void anInterruptedWaitFailsAndKeepsTheInterruptFlag() {
        broker(c -> Thread.currentThread().interrupt());

        try {
            assertThatThrownBy(this::publishOne).isInstanceOf(DeadLetterPublishException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private void publishOne() {
        MessageProperties props = new MessageProperties();
        props.setMessageId("m-1");
        publisher.publish(new Message(new byte[0], props), 5, "reason");
    }

    // What the broker does with the confirm of the copy.
    private void broker(Consumer<CorrelationData> answer) {
        doAnswer(invocation -> {
            answer.accept(invocation.getArgument(3));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}

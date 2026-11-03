package co.com.finova.wallet.rabbitmqconsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * DLQ publishing (spec §4.2): through the default exchange with the queue name as routing key, so nothing is
 * declared, and only reported as done after a positive publisher confirm for a routable message.
 */
class RabbitDeadLetterSenderTest {

    private static final String DLQ = "wallet.commands.q.dlq";

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitDeadLetterSender sender = new RabbitDeadLetterSender(rabbitTemplate, Duration.ofMillis(200));
    private final Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void publishesThroughTheDefaultExchangeAndReturnsAfterAnAck() {
        onSend(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertThatCode(() -> sender.send(message, DLQ)).doesNotThrowAnyException();

        verify(rabbitTemplate).send(eq(""), eq(DLQ), eq(message), any(CorrelationData.class));
    }

    @Test
    void failsOnANack() {
        onSend(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "queue full")));

        assertThatThrownBy(() -> sender.send(message, DLQ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nacked")
                .hasMessageContaining("queue full");
    }

    @Test
    void failsWhenTheQueueDoesNotExist() {
        onSend(correlation -> {
            correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "", DLQ));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatThrownBy(() -> sender.send(message, DLQ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void failsWithoutAConfirmInTime() {
        onSend(correlation -> { });

        assertThatThrownBy(() -> sender.send(message, DLQ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No broker confirm");
    }

    @Test
    void failsWhenTheConfirmCompletesExceptionally() {
        onSend(correlation -> correlation.getFuture().completeExceptionally(new IllegalStateException("closed")));

        assertThatThrownBy(() -> sender.send(message, DLQ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No broker confirm");
    }

    @Test
    void keepsTheInterruptFlagWhenInterruptedWhileWaiting() {
        onSend(correlation -> Thread.currentThread().interrupt());

        assertThatThrownBy(() -> sender.send(message, DLQ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private void onSend(Consumer<CorrelationData> broker) {
        doAnswer(invocation -> {
            broker.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).send(eq(""), eq(DLQ), any(Message.class), any(CorrelationData.class));
    }
}

package co.com.finova.wallet.rabbitmqconsumer;

import co.com.finova.wallet.model.common.ContractViolationException;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** What happens to each delivery: ack, retry through .retry (reject without requeue) or DLQ then ack. */
class DeliveryOutcomeHandlerTest {

    private static final String QUEUE = "wallet.commands.q";
    private static final long TAG = 42L;

    private final Channel channel = mock(Channel.class);
    private final RecordingDeadLetters deadLetters = new RecordingDeadLetters();
    private final DeliveryOutcomeHandler handler = new DeliveryOutcomeHandler(new RetryPolicy(3), deadLetters);

    @Test
    void acksAProcessedMessage() throws Exception {
        handler.process(message(List.of()), channel, QUEUE, () -> { });

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        assertThat(deadLetters.sent).isEmpty();
    }

    @Test
    void rejectsWithoutRequeueSoTheBrokerMovesItToTheRetryQueue() throws Exception {
        handler.process(message(List.of()), channel, QUEUE, () -> {
            throw new IllegalStateException("database unavailable");
        });

        verify(channel).basicReject(TAG, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(deadLetters.sent).isEmpty();
    }

    @Test
    void sendsToTheDlqAndAcksAfterThreeRetries() throws Exception {
        Message message = message(List.of(Map.of("queue", QUEUE, "reason", "rejected", "count", 3L)));

        handler.process(message, channel, QUEUE, () -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThat(deadLetters.sent).containsExactly(QUEUE + ".dlq");
        InOrder order = inOrder(channel);
        order.verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void sendsAnInvalidMessageToTheDlqAtOnce() throws Exception {
        handler.process(message(List.of()), channel, QUEUE, () -> {
            throw new ContractViolationException("unknown message type: PayWallet");
        });

        assertThat(deadLetters.sent).containsExactly(QUEUE + ".dlq");
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void keepsTheErrorOnTheDeadLetteredMessage() throws Exception {
        handler.process(message(List.of()), channel, QUEUE, () -> {
            throw new ContractViolationException("amount must be > 0");
        });

        assertThat(deadLetters.messages.get(0).getMessageProperties().<String>getHeader("x-wallet-error"))
                .isEqualTo("ContractViolationException: amount must be > 0");
    }

    @Test
    void acksOnlyAfterTheDlqConfirmed() throws Exception {
        deadLetters.onSend = () -> {
            try {
                verify(channel, never()).basicAck(anyLong(), anyBoolean());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        handler.process(message(List.of()), channel, QUEUE, () -> {
            throw new ContractViolationException("invalid");
        });

        verify(channel).basicAck(TAG, false);
    }

    @Test
    void fallsBackToARetryWhenTheDlqPublishFails() throws Exception {
        deadLetters.onSend = () -> {
            throw new IllegalStateException("no confirm from the broker");
        };

        handler.process(message(List.of()), channel, QUEUE, () -> {
            throw new ContractViolationException("invalid");
        });

        verify(channel).basicReject(TAG, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private static Message message(List<Map<String, ?>> xDeath) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        properties.setMessageId("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        properties.setConsumerQueue(QUEUE);
        if (!xDeath.isEmpty()) {
            properties.setHeader("x-death", xDeath);
        }
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    private static final class RecordingDeadLetters implements DeadLetterSender {
        final List<String> sent = new ArrayList<>();
        final List<Message> messages = new ArrayList<>();
        Runnable onSend = () -> { };

        @Override
        public void send(Message message, String deadLetterQueue) {
            onSend.run();
            sent.add(deadLetterQueue);
            messages.add(message);
        }
    }
}

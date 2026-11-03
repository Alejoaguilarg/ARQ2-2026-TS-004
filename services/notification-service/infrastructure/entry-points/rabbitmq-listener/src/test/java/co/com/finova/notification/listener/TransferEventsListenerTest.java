package co.com.finova.notification.listener;

import co.com.finova.notification.model.exceptions.PendingNotificationsException;
import co.com.finova.notification.model.exceptions.RecipientUnreachableException;
import co.com.finova.notification.model.notification.TransferOutcome;
import co.com.finova.notification.usecase.notifytransferoutcome.NotifyTransferOutcomeUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TransferEventsListenerTest {

    private static final long TAG = 7L;
    private static final String QUEUE = "notification.transfer-events.q";

    private NotifyTransferOutcomeUseCase useCase;
    private DeadLetterPublisher deadLetterPublisher;
    private Channel channel;
    private TransferEventsListener listener;

    @BeforeEach
    void setUp() {
        useCase = mock(NotifyTransferOutcomeUseCase.class);
        deadLetterPublisher = mock(DeadLetterPublisher.class);
        channel = mock(Channel.class);
        ListenerProperties properties = new ListenerProperties(QUEUE, QUEUE + ".dlq", 3, Duration.ofSeconds(5));
        listener = new TransferEventsListener(useCase, new TransferEventMapper(new ObjectMapper()),
                deadLetterPublisher, properties);
    }

    @Test
    void acksAfterNotifying() throws IOException {
        listener.onMessage(validMessage(0), channel);

        verify(useCase).execute(any(TransferOutcome.class));
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void sendsAMessageThatBreaksTheContractStraightToTheDlq() throws IOException {
        Message message = message("not json".getBytes(StandardCharsets.UTF_8), 0);

        listener.onMessage(message, channel);

        verifyNoInteractions(useCase);
        verify(deadLetterPublisher).publish(any(Message.class), startsWith("INVALID_MESSAGE"));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void sendsAnUnreachableUserStraightToTheDlq() throws IOException {
        doThrow(new RecipientUnreachableException(UUID.randomUUID(), List.of(UUID.randomUUID())))
                .when(useCase).execute(any(TransferOutcome.class));

        listener.onMessage(validMessage(0), channel);

        verify(deadLetterPublisher).publish(any(Message.class), startsWith("RECIPIENT_UNREACHABLE"));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void rejectsWithoutRequeueSoRabbitMqRetriesIn5Seconds() throws IOException {
        doThrow(new PendingNotificationsException(UUID.randomUUID(), List.of(UUID.randomUUID())))
                .when(useCase).execute(any(TransferOutcome.class));

        listener.onMessage(validMessage(2), channel);

        verify(channel).basicReject(TAG, false);
        verify(channel, never()).basicAck(TAG, false);
        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void movesToTheDlqAfterThreeRetries() throws IOException {
        doThrow(new PendingNotificationsException(UUID.randomUUID(), List.of(UUID.randomUUID())))
                .when(useCase).execute(any(TransferOutcome.class));

        listener.onMessage(validMessage(3), channel);

        verify(deadLetterPublisher).publish(any(Message.class), startsWith("RETRIES_EXHAUSTED"));
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicReject(TAG, false);
    }

    @Test
    void ifTheDlqIsUnavailableTheMessageIsNotLost() throws IOException {
        doThrow(new IllegalStateException("no confirm")).when(deadLetterPublisher)
                .publish(any(Message.class), anyString());

        listener.onMessage(message("not json".getBytes(StandardCharsets.UTF_8), 0), channel);

        verify(channel).basicReject(TAG, false);
        verify(channel, never()).basicAck(TAG, false);
    }

    private static Message validMessage(long previousRejections) {
        return message(TransferEventMapperTest.envelope("TransferCompleted",
                "\"completedAt\":\"2026-11-02T15:00:00Z\""), previousRejections);
    }

    private static Message message(byte[] body, long previousRejections) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        properties.setMessageId(UUID.randomUUID().toString());
        if (previousRejections > 0) {
            properties.setHeader("x-death", List.of(
                    Map.of("queue", QUEUE, "reason", "rejected", "count", previousRejections),
                    Map.of("queue", QUEUE + ".retry", "reason", "expired", "count", previousRejections)));
        }
        return new Message(body, properties);
    }
}

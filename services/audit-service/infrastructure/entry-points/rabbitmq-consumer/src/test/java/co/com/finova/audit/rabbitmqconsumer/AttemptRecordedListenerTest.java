package co.com.finova.audit.rabbitmqconsumer;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.record.RecordOutcome;
import co.com.finova.audit.usecase.recordattempt.RecordAttemptUseCase;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttemptRecordedListenerTest {

    private static final long TAG = 7L;
    private static final UUID EVENT_ID = UUID.fromString("8b2d4f6a-1c3e-4a5b-9d7f-2e4a6c8b0d1f");
    private static final String VALID = """
            {"eventId":"8b2d4f6a-1c3e-4a5b-9d7f-2e4a6c8b0d1f","source":"WALLET","stage":"DEBIT",
             "outcome":"APPLIED","amount":"150000.00","correlationId":"5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11",
             "occurredAt":"2026-10-19T15:00:00Z"}""";

    private RecordAttemptUseCase recordAttempt;
    private DeadLetterPublisher deadLetters;
    private Channel channel;
    private SimpleMeterRegistry meters;
    private AttemptRecordedListener listener;

    @BeforeEach
    void setUp() {
        recordAttempt = mock(RecordAttemptUseCase.class);
        deadLetters = mock(DeadLetterPublisher.class);
        channel = mock(Channel.class);
        meters = new SimpleMeterRegistry();
        listener = new AttemptRecordedListener(recordAttempt, new AttemptRecordedReader(), deadLetters, meters, 5);
    }

    @Test
    void acksAStoredAttempt() throws Exception {
        when(recordAttempt.handle(any(AttemptRecorded.class))).thenReturn(RecordOutcome.recorded(EVENT_ID, 1));

        listener.onMessage(message(VALID, null), channel);

        verify(channel).basicAck(TAG, false);
        assertEquals(1.0, count("recorded"));
    }

    @Test
    void acksADuplicate() throws Exception {
        when(recordAttempt.handle(any(AttemptRecorded.class))).thenReturn(RecordOutcome.ignoredDuplicate(EVENT_ID));

        listener.onMessage(message(VALID, null), channel);

        verify(channel).basicAck(TAG, false);
        assertEquals(1.0, count("duplicate"));
    }

    @Test
    void sendsAMalformedMessageStraightToTheDlq() throws Exception {
        Message message = message("{\"source\":\"WALLET\"}", null);

        listener.onMessage(message, channel);

        verify(deadLetters).publish(eq(message), eq(0L), startsWith("malformed"));
        verify(channel).basicAck(TAG, false);
        verify(recordAttempt, never()).handle(any(AttemptRecorded.class));
    }

    @Test
    void rejectsWithoutRequeueOnATechnicalErrorSoTheRetryQueueBringsItBack() throws Exception {
        when(recordAttempt.handle(any(AttemptRecorded.class))).thenThrow(new IllegalStateException("db down"));

        listener.onMessage(message(VALID, deaths(AttemptRecordedListener.QUEUE, 4)), channel);

        verify(channel).basicNack(TAG, false, false);
        verify(deadLetters, never()).publish(any(Message.class), anyLong(), anyString());
        assertEquals(1.0, count("retried"));
    }

    @Test
    void movesTheMessageToTheDlqAfterFiveRetries() throws Exception {
        when(recordAttempt.handle(any(AttemptRecorded.class))).thenThrow(new IllegalStateException("db down"));
        Message message = message(VALID, deaths(AttemptRecordedListener.QUEUE, 5));

        listener.onMessage(message, channel);

        verify(deadLetters).publish(eq(message), eq(5L), startsWith("retries exhausted"));
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertEquals(1.0, count("dead_lettered"));
    }

    @Test
    void doesNotAckWhenTheDlqCopyIsNotConfirmed() throws Exception {
        Message message = message("not json", null);
        doThrow(new DeadLetterPublishException("no confirm")).when(deadLetters)
                .publish(any(Message.class), anyLong(), anyString());

        listener.onMessage(message, channel);

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void onlyCountsRejectionsFromTheAuditQueue() {
        Message message = message(VALID, List.of(
                Map.of("queue", "audit.attempts.q.retry", "reason", "expired", "count", 9L),
                Map.of("queue", AttemptRecordedListener.QUEUE, "reason", "rejected", "count", 2L)));

        assertEquals(2, RetryCount.of(message, AttemptRecordedListener.QUEUE));
        assertEquals(0, RetryCount.of(message(VALID, null), AttemptRecordedListener.QUEUE));
    }

    private double count(String result) {
        return meters.get("audit.attempts").tag("result", result).counter().count();
    }

    private static List<Map<String, Object>> deaths(String queue, long count) {
        return List.of(Map.of("queue", queue, "reason", "rejected", "count", count));
    }

    private static Message message(String body, List<Map<String, Object>> xDeath) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        properties.setMessageId(EVENT_ID.toString());
        if (xDeath != null) {
            properties.setHeader("x-death", xDeath);
        }
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

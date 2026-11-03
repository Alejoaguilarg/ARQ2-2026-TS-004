package co.com.finova.gateway.rabbitmqpublisher;

import co.com.finova.gateway.model.audit.GatewayAttempt;
import co.com.finova.gateway.model.audit.GatewayAttempt.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Deque;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** spec §4.1, §4.3: AttemptRecorded to audit.events / audit.attempt, confirmed by the broker, with local retry. */
class RabbitAttemptPublisherTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GatewayAttempt ATTEMPT = new GatewayAttempt(UUID.randomUUID(), Outcome.DUPLICATE,
            "DUPLICATE_REPLAYED", "9b2c4d6e-8f0a-4b1c-9d3e-5f7a9b1c3d5e", "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c",
            "3f1e9a7c-5b2d-4c8e-9f0a-1b2c3d4e5f60", 202, "c0ffee00-0000-4000-8000-000000000001",
            Instant.parse("2026-11-03T10:15:30.123456Z"));

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final Deque<Boolean> confirms = new LinkedList<>();
    private final RabbitAttemptPublisher publisher =
            new RabbitAttemptPublisher(rabbit, Duration.ofMillis(200), 3, Duration.ofMillis(1));

    RabbitAttemptPublisherTest() {
        // Each send gets the next broker answer: ack (true), nack (false), or none at all when the deque is empty.
        doAnswer(invocation -> {
            Boolean ack = confirms.poll();
            if (ack != null) {
                invocation.<CorrelationData>getArgument(3).getFuture()
                        .complete(new CorrelationData.Confirm(ack, ack ? null : "nacked"));
            }
            return null;
        }).when(rabbit).send(eq("audit.events"), eq("audit.attempt"), any(Message.class), any(CorrelationData.class));
    }

    @AfterEach
    void stop() throws InterruptedException {
        publisher.stop();
    }

    @Test
    void writesTheEnvelopeWithTheGatewayPayload() throws Exception {
        JsonNode envelope = JSON.readTree(RabbitAttemptPublisher.envelope(ATTEMPT));
        JsonNode payload = envelope.get("payload");

        assertThat(envelope.get("messageId").asText()).isEqualTo(ATTEMPT.eventId().toString());
        assertThat(envelope.get("type").asText()).isEqualTo("AttemptRecorded");
        assertThat(envelope.get("producer").asText()).isEqualTo("api-gateway");
        assertThat(envelope.get("transferId").asText()).isEqualTo(ATTEMPT.transferId());
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-11-03T10:15:30.123456Z");
        assertThat(payload.get("eventId").asText()).isEqualTo(ATTEMPT.eventId().toString());
        assertThat(payload.get("source").asText()).isEqualTo("GATEWAY");
        assertThat(payload.get("stage").asText()).isEqualTo("REQUEST");
        assertThat(payload.get("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(payload.get("reason").asText()).isEqualTo("DUPLICATE_REPLAYED");
        assertThat(payload.get("userId").asText()).isEqualTo(ATTEMPT.userId());
        assertThat(payload.get("idempotencyKey").asText()).isEqualTo(ATTEMPT.idempotencyKey());
        assertThat(payload.get("amount").isNull()).isTrue();
        assertThat(payload.get("httpStatus").asInt()).isEqualTo(202);
        assertThat(payload.get("correlationId").asText()).isEqualTo(ATTEMPT.correlationId());
    }

    @Test
    void publishesAPersistentMessageIdentifiedByTheEventId() {
        confirms.add(true);

        publisher.publish(ATTEMPT);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit, timeout(2000)).send(eq("audit.events"), eq("audit.attempt"), sent.capture(),
                any(CorrelationData.class));
        assertThat(sent.getValue().getMessageProperties().getMessageId()).isEqualTo(ATTEMPT.eventId().toString());
        assertThat(sent.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void retriesANackAndAMissingConfirm() throws InterruptedException {
        confirms.add(false);
        confirms.add(null);
        confirms.add(true);

        publisher.publish(ATTEMPT);
        publisher.stop();

        verify(rabbit, times(3)).send(eq("audit.events"), eq("audit.attempt"), any(Message.class),
                any(CorrelationData.class));
    }

    @Test
    void givesUpAfterTheLastAttemptWithoutFailingTheCaller() throws InterruptedException {
        doAnswer(invocation -> {
            throw new IllegalStateException("broker down");
        }).when(rabbit).send(eq("audit.events"), eq("audit.attempt"), any(Message.class), any(CorrelationData.class));

        publisher.publish(ATTEMPT);
        publisher.stop();

        verify(rabbit, times(3)).send(eq("audit.events"), eq("audit.attempt"), any(Message.class),
                any(CorrelationData.class));
    }
}

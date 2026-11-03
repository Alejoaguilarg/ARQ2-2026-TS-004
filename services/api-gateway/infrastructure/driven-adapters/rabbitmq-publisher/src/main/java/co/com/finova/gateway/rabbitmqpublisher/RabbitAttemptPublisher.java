package co.com.finova.gateway.rabbitmqpublisher;

import co.com.finova.gateway.model.audit.GatewayAttempt;
import co.com.finova.gateway.model.audit.gateways.AttemptPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PreDestroy;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * spec.md §4.3: the Gateway has no outbox, it publishes AttemptRecorded directly with local retry. Publishing runs
 * off the request thread, so a slow or down broker never delays or fails a transfer answer.
 */
@Component
public class RabbitAttemptPublisher implements AttemptPublisher {

    static final String EXCHANGE = "audit.events";
    static final String ROUTING_KEY = "audit.attempt";
    static final String TYPE = "AttemptRecorded";
    static final String PRODUCER = "api-gateway";
    // shortcut: an attempt still pending when the queue is full or the process stops is only logged, not stored;
    // add an outbox if the Gateway ever needs the same guarantee as Transfer and Wallet.
    private static final int QUEUE_CAPACITY = 10_000;

    private static final System.Logger LOG = System.getLogger(RabbitAttemptPublisher.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;
    private final int maxAttempts;
    private final Duration backoff;
    private final ExecutorService executor;

    public RabbitAttemptPublisher(RabbitTemplate rabbitTemplate,
                                  @Value("${adapters.audit.confirm-timeout:5s}") Duration confirmTimeout,
                                  @Value("${adapters.audit.max-attempts:3}") int maxAttempts,
                                  @Value("${adapters.audit.backoff:1s}") Duration backoff) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "audit-publisher");
                    thread.setDaemon(true);
                    return thread;
                }, (runnable, pool) -> LOG.log(System.Logger.Level.ERROR, "Audit queue full, attempt dropped"));
    }

    @Override
    public void publish(GatewayAttempt attempt) {
        String body = envelope(attempt);
        executor.execute(() -> send(attempt, body));
    }

    @PreDestroy
    void stop() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(confirmTimeout.multipliedBy(maxAttempts).toMillis(), TimeUnit.MILLISECONDS);
    }

    private void send(GatewayAttempt attempt, String body) {
        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            try {
                CorrelationData correlation = new CorrelationData(attempt.eventId().toString());
                rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message(attempt, body), correlation);
                CorrelationData.Confirm confirm =
                        correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (confirm.isAck() && correlation.getReturned() == null) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "Broker did not take attempt " + attempt.eventId()
                        + " (try " + attemptNo + "): " + confirm.getReason());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Could not publish attempt " + attempt.eventId()
                        + " (try " + attemptNo + "): " + e.getMessage());
            }
            if (attemptNo < maxAttempts && !pause(backoff.multipliedBy(attemptNo))) {
                break;
            }
        }
        // The full message goes to the log (Loki), so the attempt can still be found and replayed by hand.
        LOG.log(System.Logger.Level.ERROR, "Audit attempt lost after " + maxAttempts + " tries: " + body);
    }

    private static boolean pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Envelope spec §4.1 with the AttemptRecorded payload of §4.4.
    static String envelope(GatewayAttempt attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", attempt.eventId());
        payload.put("source", "GATEWAY");
        payload.put("stage", "REQUEST");
        payload.put("outcome", attempt.outcome());
        payload.put("reason", attempt.reason());
        payload.put("transferId", attempt.transferId());
        payload.put("userId", attempt.userId());
        payload.put("idempotencyKey", attempt.idempotencyKey());
        payload.put("amount", null);
        payload.put("httpStatus", attempt.httpStatus());
        payload.put("correlationId", attempt.correlationId());
        payload.put("occurredAt", attempt.occurredAt());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", attempt.eventId());
        envelope.put("type", TYPE);
        envelope.put("correlationId", attempt.correlationId());
        envelope.put("transferId", attempt.transferId());
        envelope.put("producer", PRODUCER);
        envelope.put("occurredAt", attempt.occurredAt());
        envelope.put("payload", payload);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize attempt " + attempt.eventId(), e);
        }
    }

    private static Message message(GatewayAttempt attempt, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(attempt.eventId().toString());
        properties.setCorrelationId(attempt.correlationId());
        properties.setType(TYPE);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

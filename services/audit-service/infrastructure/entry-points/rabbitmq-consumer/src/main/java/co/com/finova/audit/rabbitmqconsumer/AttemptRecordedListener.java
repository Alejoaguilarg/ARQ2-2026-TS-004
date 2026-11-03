package co.com.finova.audit.rabbitmqconsumer;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.record.RecordOutcome;
import co.com.finova.audit.usecase.recordattempt.RecordAttemptUseCase;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Consumer of audit.attempts.q (spec §4.2, §4.5): one consumer thread, and the queue has a single active
 * consumer across instances, so records are appended in one total order. Manual acks:
 * <ul>
 *   <li>stored or duplicate (F5.2, F5.3) → ack;</li>
 *   <li>malformed message → copy to the DLQ, then ack (retrying cannot fix it);</li>
 *   <li>technical error (audit_db down, timeout) → reject without requeue, so it comes back after 5 s
 *       through audit.attempts.q.retry; after 5 retries → copy to the DLQ, then ack.</li>
 * </ul>
 * If the DLQ copy is not confirmed, the original is rejected and comes back through the retry queue.
 */
@Component
public class AttemptRecordedListener {

    public static final String QUEUE = "audit.attempts.q";

    private static final Logger LOG = LoggerFactory.getLogger(AttemptRecordedListener.class);
    private static final String METRIC = "audit.attempts";

    private final RecordAttemptUseCase recordAttempt;
    private final AttemptRecordedReader reader;
    private final DeadLetterPublisher deadLetters;
    private final long retryLimit;
    private final Counter recorded;
    private final Counter duplicates;
    private final Counter retried;
    private final Counter deadLettered;

    public AttemptRecordedListener(RecordAttemptUseCase recordAttempt, AttemptRecordedReader reader,
                                   DeadLetterPublisher deadLetters, MeterRegistry meters,
                                   @Value("${adapters.rabbitmq.audit-retry-limit:5}") long retryLimit) {
        this.recordAttempt = recordAttempt;
        this.reader = reader;
        this.deadLetters = deadLetters;
        this.retryLimit = retryLimit;
        this.recorded = counter(meters, "recorded");
        this.duplicates = counter(meters, "duplicate");
        this.retried = counter(meters, "retried");
        this.deadLettered = counter(meters, "dead_lettered");
    }

    @RabbitListener(id = "audit-attempts", queues = QUEUE, ackMode = "MANUAL", concurrency = "1")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String messageId = message.getMessageProperties().getMessageId();
        long retries = RetryCount.of(message, QUEUE);

        AttemptRecorded attempt;
        try {
            attempt = reader.read(message.getBody());
        } catch (MalformedAttemptException e) {
            LOG.error("malformed AttemptRecorded messageId={}: {}", messageId, e.getMessage());
            deadLetter(message, channel, tag, retries, "malformed: " + e.getMessage());
            return;
        }

        try {
            RecordOutcome outcome = recordAttempt.handle(attempt);
            (outcome.duplicate() ? duplicates : recorded).increment();
            channel.basicAck(tag, false);
        } catch (RuntimeException e) {
            if (retries >= retryLimit) {
                LOG.error("AttemptRecorded eventId={} transferId={} correlationId={} failed after {} retries;"
                        + " moving it to the DLQ", attempt.eventId(), attempt.transferId(),
                        attempt.correlationId(), retries, e);
                deadLetter(message, channel, tag, retries, "retries exhausted: " + e);
            } else {
                LOG.warn("AttemptRecorded eventId={} transferId={} correlationId={} failed (retry {} of {}),"
                        + " retrying in 5 s: {}", attempt.eventId(), attempt.transferId(),
                        attempt.correlationId(), retries + 1, retryLimit, e.toString());
                retried.increment();
                channel.basicNack(tag, false, false);
            }
        }
    }

    private void deadLetter(Message message, Channel channel, long tag, long retries, String reason)
            throws IOException {
        try {
            deadLetters.publish(message, retries, reason);
            deadLettered.increment();
            channel.basicAck(tag, false);
        } catch (RuntimeException e) {
            LOG.error("could not copy messageId={} to the DLQ; it goes back through the retry queue",
                    message.getMessageProperties().getMessageId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    private static Counter counter(MeterRegistry meters, String result) {
        return Counter.builder(METRIC)
                .description("AttemptRecorded messages consumed from audit.attempts.q, by result")
                .tag("result", result)
                .register(meters);
    }
}

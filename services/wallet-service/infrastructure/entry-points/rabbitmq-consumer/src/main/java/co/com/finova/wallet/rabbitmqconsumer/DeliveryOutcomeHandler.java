package co.com.finova.wallet.rabbitmqconsumer;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Runs the work for one delivery and settles it with a manual ack (spec §4.2):
 * success → ack; RETRY → reject without requeue, so the queue dead-letters it to .retry and it comes back
 * after 5 s; DEAD_LETTER → publish to .dlq with confirm, then ack. If the DLQ publish fails, the message is
 * rejected like a retry, so it is never lost.
 */
public class DeliveryOutcomeHandler {

    static final String ERROR_HEADER = "x-wallet-error";
    private static final String DLQ_SUFFIX = ".dlq";
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryOutcomeHandler.class);

    private final RetryPolicy policy;
    private final DeadLetterSender deadLetters;

    public DeliveryOutcomeHandler(RetryPolicy policy, DeadLetterSender deadLetters) {
        this.policy = policy;
        this.deadLetters = deadLetters;
    }

    public void process(Message message, Channel channel, String queue, Runnable work) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            work.run();
        } catch (RuntimeException failure) {
            settleFailure(message, channel, queue, tag, failure);
            return;
        }
        channel.basicAck(tag, false);
    }

    private void settleFailure(Message message, Channel channel, String queue, long tag, RuntimeException failure)
            throws IOException {
        List<Map<String, ?>> xDeath = xDeath(message.getMessageProperties());
        long retries = RetryPolicy.rejectedCount(queue, xDeath);
        if (policy.decide(failure, queue, xDeath) == RetryPolicy.Decision.RETRY) {
            LOG.warn("Delivery failed, retrying in the .retry queue: {} retries={} messageId={} queue={}"
                            + " correlationId={} transferId={}", describe(failure), retries,
                    message.getMessageProperties().getMessageId(), queue, MDC.get("correlationId"),
                    MDC.get("transferId"), failure);
            channel.basicReject(tag, false);
            return;
        }
        String deadLetterQueue = queue + DLQ_SUFFIX;
        try {
            message.getMessageProperties().setHeader(ERROR_HEADER, describe(failure));
            deadLetters.send(message, deadLetterQueue);
        } catch (RuntimeException dlqFailure) {
            LOG.error("DLQ publish failed, retrying in the .retry queue: {} messageId={} queue={}"
                            + " correlationId={} transferId={}", dlqFailure.getMessage(),
                    message.getMessageProperties().getMessageId(), queue, MDC.get("correlationId"),
                    MDC.get("transferId"), dlqFailure);
            channel.basicReject(tag, false);
            return;
        }
        LOG.error("Message sent to {}: {} retries={} messageId={} correlationId={} transferId={}",
                deadLetterQueue, describe(failure), retries, message.getMessageProperties().getMessageId(),
                MDC.get("correlationId"), MDC.get("transferId"), failure);
        channel.basicAck(tag, false);
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> xDeath(MessageProperties properties) {
        Object header = properties.getHeaders().get("x-death");
        return header instanceof List<?> list ? (List<Map<String, ?>>) list : List.of();
    }
}

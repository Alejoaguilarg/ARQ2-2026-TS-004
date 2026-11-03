package co.com.finova.transfer.rabbitmqconsumer;

import co.com.finova.transfer.model.message.MessageEnvelope;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

/**
 * correlationId and transferId of the message being consumed, in the MDC of the listener thread (spec §1.4),
 * so every log line of the delivery carries them. Before the body is read, the AMQP correlation-id property
 * is the best value available; once the envelope is read, its fields replace it.
 */
final class MessageLogContext implements AutoCloseable {

    static final String CORRELATION_ID = "correlationId";
    static final String TRANSFER_ID = "transferId";
    static final String MESSAGE_ID = "messageId";

    private MessageLogContext(Message message) {
        put(CORRELATION_ID, message.getMessageProperties().getCorrelationId());
        put(MESSAGE_ID, message.getMessageProperties().getMessageId());
    }

    static MessageLogContext open(Message message) {
        return new MessageLogContext(message);
    }

    void fill(MessageEnvelope<?> envelope) {
        put(CORRELATION_ID, envelope.correlationId().toString());
        put(MESSAGE_ID, envelope.messageId().toString());
        put(TRANSFER_ID, envelope.transferId() == null ? null : envelope.transferId().toString());
    }

    @Override
    public void close() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(TRANSFER_ID);
        MDC.remove(MESSAGE_ID);
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}

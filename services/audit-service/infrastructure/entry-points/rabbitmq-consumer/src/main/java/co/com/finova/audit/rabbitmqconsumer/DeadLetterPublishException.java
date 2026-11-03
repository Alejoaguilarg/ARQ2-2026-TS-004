package co.com.finova.audit.rabbitmqconsumer;

/** The broker did not confirm the copy sent to the DLQ; the original message must not be acked. */
public class DeadLetterPublishException extends RuntimeException {

    public DeadLetterPublishException(String message) {
        super(message);
    }

    public DeadLetterPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}

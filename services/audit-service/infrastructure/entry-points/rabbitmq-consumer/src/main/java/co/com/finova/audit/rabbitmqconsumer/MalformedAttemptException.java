package co.com.finova.audit.rabbitmqconsumer;

/**
 * The message is not a valid AttemptRecorded (spec §4.1, §4.4). Retrying cannot fix it, so it goes
 * straight to audit.attempts.q.dlq, where it raises an alert and waits for manual review.
 */
public class MalformedAttemptException extends RuntimeException {

    public MalformedAttemptException(String message) {
        super(message);
    }

    public MalformedAttemptException(String message, Throwable cause) {
        super(message, cause);
    }
}

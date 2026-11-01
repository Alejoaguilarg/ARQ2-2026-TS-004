package co.com.finova.transfer.model.message;

public class MessagePublishException extends RuntimeException {

    public MessagePublishException(String message) {
        super(message);
    }

    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}

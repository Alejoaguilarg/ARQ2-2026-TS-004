package co.com.finova.wallet.model.message;

public class MessagePublishException extends RuntimeException {

    public MessagePublishException(String message) {
        super(message);
    }

    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}

package co.com.finova.transfer.model.message.gateways;

public interface ProcessedMessageRepository {
    boolean exists(String messageId, String consumer);
    void save(String messageId, String consumer);
}

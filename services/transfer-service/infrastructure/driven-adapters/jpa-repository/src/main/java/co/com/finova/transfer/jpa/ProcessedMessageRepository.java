package co.com.finova.transfer.jpa;

import org.springframework.data.repository.CrudRepository;

public interface ProcessedMessageRepository extends CrudRepository<ProcessedMessage, String> {
    boolean existsByMessageIdAndConsumer(String messageId, String consumer);
}

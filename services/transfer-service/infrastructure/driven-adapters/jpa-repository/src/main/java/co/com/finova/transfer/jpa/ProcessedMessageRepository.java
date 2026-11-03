package co.com.finova.transfer.jpa;

import org.springframework.data.repository.CrudRepository;

public interface ProcessedMessageRepository extends CrudRepository<ProcessedMessage, String> {
    boolean existsByTransferIdAndConsumer(String transferId, String consumer);
}

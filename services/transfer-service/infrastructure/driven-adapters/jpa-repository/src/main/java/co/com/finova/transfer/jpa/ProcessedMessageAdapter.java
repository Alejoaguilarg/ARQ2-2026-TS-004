package co.com.finova.transfer.jpa;

import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class ProcessedMessageAdapter implements co.com.finova.transfer.model.message.gateways.ProcessedMessageRepository {

    private final ProcessedMessageRepository repository;

    public ProcessedMessageAdapter(ProcessedMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean exists(String messageId, String consumer) {
        return repository.existsByMessageIdAndConsumer(messageId, consumer);
    }

    @Override
    public void save(String messageId, String consumer) {
        ProcessedMessage entity = new ProcessedMessage(messageId, consumer, Instant.now(), null, null);
        repository.save(entity);
    }
}

package co.com.finova.transfer.jpa;

import co.com.finova.transfer.model.message.gateways.OutboxRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class OutboxMessageAdapter implements OutboxRepository {

    private final OutboxMessageRepository repository;

    public OutboxMessageAdapter(OutboxMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(co.com.finova.transfer.model.message.gateways.OutboxMessage message) {
        OutboxMessage entity = new OutboxMessage(
            message.getId(),
            message.getExchange(),
            message.getRoutingKey(),
            message.getPayload(),
            message.getCreatedAt(),
            message.getPublishedAt()
        );
        repository.save(entity);
    }

    @Override
    public List<co.com.finova.transfer.model.message.gateways.OutboxMessage> findPending() {
        return repository.findByPublishedAtIsNull().stream()
            .map(e -> new co.com.finova.transfer.model.message.gateways.OutboxMessage(
                e.getId(), e.getExchange(), e.getRoutingKey(),
                e.getPayload(), e.getCreatedAt(), e.getPublishedAt()
            ))
            .collect(Collectors.toList());
    }
}

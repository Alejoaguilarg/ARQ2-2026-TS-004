package co.com.finova.transfer.jpa;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface OutboxMessageRepository extends CrudRepository<OutboxMessage, String> {
    List<OutboxMessage> findByPublishedAtIsNull();
}

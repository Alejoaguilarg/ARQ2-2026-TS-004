package co.com.finova.transfer.model.message.gateways;

import java.util.List;

public interface OutboxRepository {
    void save(OutboxMessage message);
    List<OutboxMessage> findPending();
}

package co.com.finova.wallet.model.outbox.gateways;

import co.com.finova.wallet.model.outbox.OutboxMessage;

public interface OutboxRepository {

    /** Inserts a pending row in the same transaction as the state change (spec §1.4). */
    void save(OutboxMessage message);
}

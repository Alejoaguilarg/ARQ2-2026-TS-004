package co.com.finova.transfer.model.outbox.gateways;

import co.com.finova.transfer.model.outbox.OutboxMessage;

import java.util.UUID;

public interface OutboxRepository {

    /** Inserts a pending row in the same transaction as the state change (spec §1.4). */
    void save(OutboxMessage message);

    /**
     * Makes a row pending again (published_at = null), so the relay publishes the same message with the
     * same messageId (spec §4.6 rule 8: the original DebitWallet or ReverseDebit is republished).
     */
    void republish(UUID id);
}

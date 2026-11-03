package co.com.finova.transfer.model.outbox.gateways;

import co.com.finova.transfer.model.outbox.PendingOutboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the outbox relay needs (spec §1.4, ADR 002). Both calls run inside the relay transaction. */
public interface OutboxRelayRepository {

    /**
     * Up to limit pending rows, oldest first, locked until the transaction ends. Rows another relay
     * instance holds are skipped, so two instances never publish the same row at the same time.
     */
    List<PendingOutboxMessage> lockPending(int limit);

    void markPublished(UUID id, Instant publishedAt);
}

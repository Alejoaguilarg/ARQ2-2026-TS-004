package co.com.finova.transfer.model.outbox.gateways;

import co.com.finova.transfer.model.outbox.PendingOutboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the outbox relay needs (spec §1.4, ADR 002). Every call runs inside the relay transaction. */
public interface OutboxRelayRepository {

    /**
     * Up to limit pending rows that are due at now (never failed, or their retry time has come), oldest first,
     * locked until the transaction ends. Rows another relay instance holds are skipped, so two instances never
     * publish the same row at the same time.
     */
    List<PendingOutboxMessage> lockPending(int limit, Instant now);

    void markPublished(UUID id, Instant publishedAt);

    /** A failed publish (audit finding H1): the row stays pending and is not tried again before nextAttemptAt. */
    void recordFailure(UUID id, int attempts, Instant nextAttemptAt, String error);
}

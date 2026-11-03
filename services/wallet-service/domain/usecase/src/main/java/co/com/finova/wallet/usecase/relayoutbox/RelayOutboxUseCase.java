package co.com.finova.wallet.usecase.relayoutbox;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.message.gateways.MessagePublisher;
import co.com.finova.wallet.model.outbox.OutboxRetryBackoff;
import co.com.finova.wallet.model.outbox.PendingOutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRelayRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Outbox relay (spec §1.4, ADR 002): publishes the pending outbox rows that are due, in creation order, each one
 * only after the broker confirms it, and marks it as published. Delivery is at-least-once; consumers deduplicate
 * on messageId.
 * <p>
 * Pending item 28: a failed publish records the attempt and a retry time that grows with each failure
 * ({@link OutboxRetryBackoff}), logs an ERROR, and ends the run. Until its retry time the row is skipped, so it
 * never blocks the rows after it, and it is never dropped. Ending the run keeps a broker outage to one confirm
 * timeout per run. Spec §4.5: no Wallet queue requires ordering, so a retried row may go out after newer ones.
 */
public class RelayOutboxUseCase {

    private static final System.Logger LOG = System.getLogger(RelayOutboxUseCase.class.getName());

    private final OutboxRelayRepository outbox;
    private final MessagePublisher publisher;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final OutboxRetryBackoff backoff;

    public RelayOutboxUseCase(OutboxRelayRepository outbox, MessagePublisher publisher,
                              TransactionRunner transactions, Clock clock, OutboxRetryBackoff backoff) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transactions = transactions;
        this.clock = clock;
        this.backoff = backoff;
    }

    /** Publishes up to batchSize due rows and returns how many were published. */
    public int relay(int batchSize) {
        Contract.require(batchSize > 0, "batchSize must be > 0: " + batchSize);
        return transactions.inTransaction(() -> publishBatch(outbox.lockPending(batchSize, clock.instant())));
    }

    private int publishBatch(List<PendingOutboxMessage> pending) {
        int published = 0;
        for (PendingOutboxMessage message : pending) {
            if (!publish(message)) {
                break;
            }
            outbox.markPublished(message.id(), clock.instant());
            published++;
        }
        return published;
    }

    // Any publish error (no confirm, nack, unroutable, broker down) leaves the row pending until its retry time.
    private boolean publish(PendingOutboxMessage message) {
        try {
            publisher.publish(message.toOutbound());
            return true;
        } catch (RuntimeException e) {
            int attempts = message.attempts() + 1;
            Instant nextAttemptAt = clock.instant().plus(backoff.delayAfter(attempts));
            outbox.recordFailure(message.id(), attempts, nextAttemptAt, e.getMessage());
            LOG.log(System.Logger.Level.ERROR, () -> "Outbox publish failed, the row stays pending: "
                    + e.getMessage()
                    + " messageId=" + message.id()
                    + " attempt=" + attempts
                    + " nextAttemptAt=" + nextAttemptAt
                    + " exchange=" + message.exchange()
                    + " routingKey=" + message.routingKey()
                    + " correlationId=" + message.correlationId()
                    + " transferId=" + message.transferId());
            return false;
        }
    }
}

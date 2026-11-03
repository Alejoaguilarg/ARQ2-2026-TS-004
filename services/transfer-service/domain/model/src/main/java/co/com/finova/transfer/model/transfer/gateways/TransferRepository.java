package co.com.finova.transfer.model.transfer.gateways;

import co.com.finova.transfer.model.transfer.Transfer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** transfers of transfer_db (spec §2 Transfer). Writes run inside the caller's transaction. */
public interface TransferRepository {

    /** Fails with DuplicateIdempotencyKeyException on UNIQUE(sender_user_id, idempotency_key). */
    void insert(Transfer transfer);

    void update(Transfer transfer);

    Optional<Transfer> findById(UUID transferId);

    /** The row locked until the transaction ends, so the consumer and the sweeper never apply two changes at once. */
    Optional<Transfer> lockById(UUID transferId);

    Optional<Transfer> findByIdempotencyKey(UUID senderUserId, UUID idempotencyKey);

    /**
     * Non-terminal transfers whose last command has waited too long (spec §4.6 rule 8): PENDING sent before
     * pendingBefore, DEBITED before debitedBefore, COMPENSATING before compensatingBefore. Oldest first.
     */
    List<UUID> findDue(Instant pendingBefore, Instant debitedBefore, Instant compensatingBefore, int limit);
}

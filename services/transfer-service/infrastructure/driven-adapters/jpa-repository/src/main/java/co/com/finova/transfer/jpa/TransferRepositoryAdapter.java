package co.com.finova.transfer.jpa;

import co.com.finova.transfer.model.Transfer;
import co.com.finova.transfer.model.TransferRepository;
import co.com.finova.transfer.model.TransferStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepositoryAdapter implements TransferRepository {

    private final TransferJpaRepository repository;

    public TransferRepositoryAdapter(TransferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Transfer transfer) {
        TransferJpaEntity entity = new TransferJpaEntity(
            transfer.getTransferId(),
            transfer.getSenderUserId(),
            transfer.getRecipientUserId(),
            transfer.getAmount(),
            transfer.getCurrency(),
            transfer.getIdempotencyKey(),
            transfer.getRequestHash(),
            transfer.getCorrelationId()
        );
        entity.setStatus(transfer.getStatus().name());
        entity.setFailureReason(transfer.getFailureReason());
        entity.setCreditAttempts(transfer.getCreditAttempts());
        entity.setLastCommandAt(transfer.getLastCommandAt());
        entity.setCreatedAt(transfer.getCreatedAt());
        entity.setUpdatedAt(transfer.getUpdatedAt());
        entity.setCompletedAt(transfer.getCompletedAt());
        repository.save(entity);
    }

    @Override
    public Optional<Transfer> findByTransferId(String transferId) {
        return repository.findById(transferId).map(this::toDomain);
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey).map(this::toDomain);
    }

    private Transfer toDomain(TransferJpaEntity e) {
        Transfer t = new Transfer(
            e.getTransferId(), e.getSenderUserId(), e.getRecipientUserId(),
            e.getAmount(), e.getCurrency(), e.getIdempotencyKey(),
            e.getRequestHash(), e.getCorrelationId()
        );
        t.setStatus(TransferStatus.valueOf(e.getStatus()));
        t.setFailureReason(e.getFailureReason());
        t.setCreditAttempts(e.getCreditAttempts());
        t.setLastCommandAt(e.getLastCommandAt());
        t.setCreatedAt(e.getCreatedAt());
        t.setUpdatedAt(e.getUpdatedAt());
        t.setCompletedAt(e.getCompletedAt());
        return t;
    }
}

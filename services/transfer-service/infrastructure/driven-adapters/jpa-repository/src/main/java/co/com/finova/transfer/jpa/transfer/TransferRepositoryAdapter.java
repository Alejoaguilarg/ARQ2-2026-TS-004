package co.com.finova.transfer.jpa.transfer;

import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import co.com.finova.transfer.jpa.transfer.TransferJpaEntity;
import co.com.finova.transfer.jpa.transfer.TransferJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepositoryAdapter implements co.com.finova.transfer.model.transfer.gateways.TransferRepository {

    private final TransferJpaRepository repository;

    public TransferRepositoryAdapter(TransferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Transfer transfer) {
        repository.save(toEntity(transfer));
    }

    @Override
    public void update(Transfer transfer) {
        repository.save(toEntity(transfer));
    }

    @Override
    public Transfer findByTransferId(UUID transferId) {
        return repository.findByTransferId(transferId).map(this::toDomain).orElse(null);
    }

    @Override
    public Transfer lockById(UUID transferId) {
        return repository.findByTransferId(transferId).map(this::toDomain).orElse(null);
    }

    @Override
    public Transfer findByIdempotencyKey(UUID senderUserId, UUID idempotencyKey) {
        return repository.findByIdempotencyKey(senderUserId, idempotencyKey).map(this::toDomain).orElse(null);
    }

    @Override
    public List<Transfer> findByPending(Instant pendingBefore, Instant debitedBefore, Instant compensatingBefore, int limit) {
        return repository.findByPending(pendingBefore, debitedBefore, compensatingBefore, limit)
            .stream()
            .map(this::toDomain)
            .collect(java.util.stream.Collectors.toList());
    }

    private TransferJpaEntity toEntity(Transfer transfer) {
        return new TransferJpaEntity(
            transfer.getTransferId(),
            transfer.getSenderUserId(),
            transfer.getRecipientUserId(),
            transfer.getAmount(),
            transfer.getCurrency(),
            transfer.getIdempotencyKey(),
            transfer.getRequestHash(),
            transfer.getCorrelationId(),
            transfer.getStatus().name(),
            transfer.getFailureReason(),
            transfer.getCreditAttempts(),
            transfer.getLastCommandAt(),
            transfer.getCreatedAt(),
            transfer.getUpdatedAt(),
            transfer.getCompletedAt()
        );
    }

    private Transfer toDomain(TransferJpaEntity entity) {
        Transfer transfer = new Transfer(
            entity.getTransferId(),
            entity.getSenderUserId(),
            entity.getRecipientUserId(),
            entity.getAmount(),
            entity.getCurrency(),
            entity.getIdempotencyKey(),
            entity.getRequestHash(),
            entity.getCorrelationId()
        );
        transfer.setStatus(TransferStatus.valueOf(entity.getStatus()));
        transfer.setFailureReason(entity.getFailureReason());
        transfer.setCreditAttempts(entity.getCreditAttempts());
        transfer.setLastCommandAt(entity.getLastCommandAt());
        transfer.setCreatedAt(entity.getCreatedAt());
        transfer.setUpdatedAt(entity.getUpdatedAt());
        transfer.setCompletedAt(entity.getCompletedAt());
        return transfer;
    }
}

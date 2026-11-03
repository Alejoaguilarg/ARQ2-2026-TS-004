package co.com.finova.transfer.usecase.support;

import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.common.gateways.TransactionRunner;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.message.payload.MessagePayload;
import co.com.finova.transfer.model.outbox.OutboxMessage;
import co.com.finova.transfer.model.outbox.gateways.OutboxRepository;
import co.com.finova.transfer.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.transfer.model.transfer.DuplicateIdempotencyKeyException;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import co.com.finova.transfer.usecase.saga.SagaOutbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The ports of the use cases in memory. The transaction runner keeps a copy of every store and restores it when
 * the work throws, so a failed transaction leaves nothing behind, as in transfer_db.
 */
public final class InMemoryPorts {

    public static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    public final Map<UUID, Transfer> transfers = new LinkedHashMap<>();
    public final List<OutboxMessage> outbox = new ArrayList<>();
    public final Set<UUID> republished = new HashSet<>();
    public final Set<String> processed = new HashSet<>();
    public Instant now = NOW;
    public int transactionsRun;

    public final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    public final IdGenerator ids = UUID::randomUUID;

    public final TransferRepository transferRepository = new TransferRepository() {
        @Override
        public void insert(Transfer transfer) {
            boolean taken = transfers.values().stream().anyMatch(t -> t.senderUserId().equals(transfer.senderUserId())
                    && t.idempotencyKey().equals(transfer.idempotencyKey()));
            if (taken) {
                throw new DuplicateIdempotencyKeyException(new IllegalStateException("unique"));
            }
            transfers.put(transfer.transferId(), transfer);
        }

        @Override
        public void update(Transfer transfer) {
            if (transfers.replace(transfer.transferId(), transfer) == null) {
                throw new IllegalStateException("not found");
            }
        }

        @Override
        public Optional<Transfer> findById(UUID transferId) {
            return Optional.ofNullable(transfers.get(transferId));
        }

        @Override
        public Optional<Transfer> lockById(UUID transferId) {
            return findById(transferId);
        }

        @Override
        public Optional<Transfer> findByIdempotencyKey(UUID senderUserId, UUID idempotencyKey) {
            return transfers.values().stream().filter(t -> t.senderUserId().equals(senderUserId)
                    && t.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public List<UUID> findDue(Instant pendingBefore, Instant debitedBefore, Instant compensatingBefore,
                                  int limit) {
            return transfers.values().stream()
                    .filter(t -> (t.status() == TransferStatus.PENDING && !t.lastCommandAt().isAfter(pendingBefore))
                            || (t.status() == TransferStatus.DEBITED && !t.lastCommandAt().isAfter(debitedBefore))
                            || (t.status() == TransferStatus.COMPENSATING
                            && !t.lastCommandAt().isAfter(compensatingBefore)))
                    .sorted(Comparator.comparing(Transfer::lastCommandAt))
                    .limit(limit)
                    .map(Transfer::transferId)
                    .toList();
        }
    };

    public final OutboxRepository outboxRepository = new OutboxRepository() {
        @Override
        public void save(OutboxMessage message) {
            outbox.add(message);
        }

        @Override
        public void republish(UUID id) {
            if (outbox.stream().noneMatch(m -> m.id().equals(id))) {
                throw new IllegalStateException("outbox row " + id + " not found");
            }
            republished.add(id);
        }
    };

    public final ProcessedMessageRepository processedRepository =
            (messageId, consumer, processedAt) -> processed.add(messageId + "/" + consumer);

    public final TransactionRunner transactions = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            transactionsRun++;
            Map<UUID, Transfer> transfersBefore = new LinkedHashMap<>(transfers);
            List<OutboxMessage> outboxBefore = new ArrayList<>(outbox);
            Set<String> processedBefore = new HashSet<>(processed);
            try {
                return work.get();
            } catch (RuntimeException e) {
                transfers.clear();
                transfers.putAll(transfersBefore);
                outbox.clear();
                outbox.addAll(outboxBefore);
                processed.clear();
                processed.addAll(processedBefore);
                throw e;
            }
        }
    };

    public final SagaOutbox saga = new SagaOutbox(outboxRepository, ids);

    /** The payloads written to the outbox, oldest first. */
    public List<MessagePayload> written() {
        return outbox.stream().map(m -> (MessagePayload) m.envelope().payload()).toList();
    }

    public List<MessagePayload> written(MessageType type) {
        return written().stream().filter(p -> p.type() == type).toList();
    }

    @SuppressWarnings("unchecked")
    public <P extends MessagePayload> P only(Class<P> type) {
        List<MessagePayload> found = written().stream().filter(type::isInstance).toList();
        if (found.size() != 1) {
            throw new AssertionError("expected one " + type.getSimpleName() + " but found " + found);
        }
        return (P) found.get(0);
    }

    public MessageEnvelope<?> envelopeOf(MessagePayload payload) {
        return outbox.stream().filter(m -> m.envelope().payload() == payload).findFirst().orElseThrow().envelope();
    }
}

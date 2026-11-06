package co.com.finova.transfer.usecase.createtransfer;

import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptRecorded;
import co.com.finova.transfer.model.message.payload.AttemptSource;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.DebitWallet;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.outbox.OutboxMessage;
import co.com.finova.transfer.model.transfer.DuplicateIdempotencyKeyException;
import co.com.finova.transfer.model.transfer.IdempotencyKeyReusedException;
import co.com.finova.transfer.model.transfer.SelfTransferNotAllowedException;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.SagaTelemetry;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import co.com.finova.transfer.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §3.8, §3.1.1 defense in depth, §4.6 rule 1 and the REQUEST-stage audit records of §4.8. */
class CreateTransferUseCaseTest {

    private static final String HASH = "c".repeat(64);
    private static final String OTHER_HASH = "d".repeat(64);

    private final InMemoryPorts ports = new InMemoryPorts();
    private final CreateTransferUseCase useCase = new CreateTransferUseCase(ports.transferRepository, ports.saga,
            ports.transactions, ports.ids, ports.clock, SagaTelemetry.NOOP);
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    private TransferRequest request(UUID to, String hash) {
        return new TransferRequest(sender, to, Money.parse("150000.00"), Currency.COP, "Dinner", key, hash);
    }

    @Test
    void anAcceptedTransferIsPendingWithItsDebitAndItsAuditInOneTransaction_rule1() {
        CreateTransferUseCase.Result result = useCase.handle(request(recipient, HASH), correlationId);

        Transfer transfer = result.transfer();
        assertThat(result.replayed()).isFalse();
        assertThat(ports.transactionsRun).isEqualTo(1);
        assertThat(ports.transfers).containsValue(transfer);
        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.correlationId()).isEqualTo(correlationId);
        assertThat(transfer.createdAt()).isEqualTo(InMemoryPorts.NOW);

        DebitWallet debit = ports.only(DebitWallet.class);
        assertThat(debit).isEqualTo(new DebitWallet(transfer.transferId(), sender, Money.parse("150000.00"),
                Currency.COP));
        MessageEnvelope<?> envelope = ports.envelopeOf(debit);
        assertThat(envelope.messageId()).isEqualTo(transfer.lastCommandId());
        assertThat(envelope.correlationId()).isEqualTo(correlationId);
        assertThat(envelope.transferId()).isEqualTo(transfer.transferId());

        AttemptRecorded attempt = ports.only(AttemptRecorded.class);
        assertThat(attempt.source()).isEqualTo(AttemptSource.TRANSFER);
        assertThat(attempt.stage()).isEqualTo(AttemptStage.REQUEST);
        assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.ACCEPTED);
        assertThat(attempt.reason()).isNull();
        assertThat(attempt.userId()).isEqualTo(sender);
        assertThat(attempt.idempotencyKey()).isEqualTo(key.toString());
        assertThat(attempt.amount()).isEqualTo(Money.parse("150000.00"));
        assertThat(attempt.statusCode()).isEqualTo(202);
        assertThat(attempt.transferId()).isEqualTo(transfer.transferId());
    }

    @Test
    void theSameKeyAndBodyReturnTheExistingTransfer_F3_7() {
        Transfer first = useCase.handle(request(recipient, HASH), correlationId).transfer();

        CreateTransferUseCase.Result again = useCase.handle(request(recipient, HASH), UUID.randomUUID());

        assertThat(again.replayed()).isTrue();
        assertThat(again.transfer()).isEqualTo(first);
        assertThat(ports.transfers).hasSize(1);
        assertThat(ports.written(MessageType.DEBIT_WALLET)).hasSize(1);
        AttemptRecorded duplicate = (AttemptRecorded) ports.written(MessageType.ATTEMPT_RECORDED).get(1);
        assertThat(duplicate.outcome()).isEqualTo(AttemptOutcome.DUPLICATE);
        assertThat(duplicate.reason()).isEqualTo("DUPLICATE_TRANSFER");
        assertThat(duplicate.transferId()).isEqualTo(first.transferId());
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefusedAndAudited_s3_1_1() {
        useCase.handle(request(recipient, HASH), correlationId);

        assertThatThrownBy(() -> useCase.handle(request(recipient, OTHER_HASH), correlationId))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(ports.transfers).hasSize(1);
        AttemptRecorded rejected = (AttemptRecorded) ports.written(MessageType.ATTEMPT_RECORDED).get(1);
        assertThat(rejected.outcome()).isEqualTo(AttemptOutcome.REJECTED);
        assertThat(rejected.reason()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(rejected.transferId()).isNull();
    }

    @Test
    void aTransferToOneselfIsRefusedAndAudited() {
        assertThatThrownBy(() -> useCase.handle(request(sender, HASH), correlationId))
                .isInstanceOf(SelfTransferNotAllowedException.class);

        assertThat(ports.transfers).isEmpty();
        AttemptRecorded rejected = ports.only(AttemptRecorded.class);
        assertThat(rejected.reason()).isEqualTo("SELF_TRANSFER_NOT_ALLOWED");
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.correlationId()).isEqualTo(correlationId);
        assertThat(ports.written(MessageType.DEBIT_WALLET)).isEmpty();
    }

    @Test
    void anInvalidRequestIsAuditedWithTheRawKey() {
        useCase.rejectInvalid(sender, "not-a-uuid", correlationId);

        AttemptRecorded rejected = ports.only(AttemptRecorded.class);
        assertThat(rejected.stage()).isEqualTo(AttemptStage.REQUEST);
        assertThat(rejected.outcome()).isEqualTo(AttemptOutcome.REJECTED);
        assertThat(rejected.reason()).isEqualTo("VALIDATION_ERROR");
        assertThat(rejected.idempotencyKey()).isEqualTo("not-a-uuid");
        assertThat(rejected.statusCode()).isEqualTo(400);
        OutboxMessage row = ports.outbox.get(0);
        assertThat(row.envelope().transferId()).isNull();
        assertThat(row.envelope().messageId()).isEqualTo(rejected.eventId());
    }

    @Test
    void aConcurrentRequestThatWonTheUniqueKeyIsAnsweredWithItsTransfer() {
        Transfer winner = Transfer.create(request(recipient, HASH), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.parse("2026-11-03T09:59:59Z"));
        // First lookup misses (the winner has not committed yet); the insert then hits the UNIQUE constraint.
        TransferRepository racing = new TransferRepository() {
            private boolean committed;

            @Override
            public void insert(Transfer transfer) {
                committed = true;
                throw new DuplicateIdempotencyKeyException(new IllegalStateException("unique"));
            }

            @Override
            public Optional<Transfer> findByIdempotencyKey(UUID senderUserId, UUID idempotencyKey) {
                return committed ? Optional.of(winner) : Optional.empty();
            }

            @Override
            public void update(Transfer transfer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Transfer> findById(UUID transferId) {
                return Optional.empty();
            }

            @Override
            public Optional<Transfer> lockById(UUID transferId) {
                return Optional.empty();
            }

            @Override
            public List<UUID> findDue(Instant a, Instant b, Instant c, int limit) {
                return List.of();
            }
        };
        CreateTransferUseCase raced = new CreateTransferUseCase(racing, ports.saga, ports.transactions, ports.ids,
                ports.clock, SagaTelemetry.NOOP);

        CreateTransferUseCase.Result result = raced.handle(request(recipient, HASH), correlationId);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isEqualTo(winner);
        assertThat(ports.written(MessageType.DEBIT_WALLET)).isEmpty();
        assertThat(ports.only(AttemptRecorded.class).reason()).isEqualTo("DUPLICATE_TRANSFER");
    }
}

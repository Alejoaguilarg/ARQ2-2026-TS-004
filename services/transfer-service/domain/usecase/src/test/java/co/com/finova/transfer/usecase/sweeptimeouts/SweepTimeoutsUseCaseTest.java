package co.com.finova.transfer.usecase.sweeptimeouts;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptRecorded;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.CreditWallet;
import co.com.finova.transfer.model.message.payload.ReverseDebit;
import co.com.finova.transfer.model.message.payload.ReverseReason;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §4.6 rules 5, 7 and 8, F4.3, F4.10, F4.11 and F4.13. */
class SweepTimeoutsUseCaseTest {

    private static final Money AMOUNT = Money.parse("150000.00");
    private static final Instant NOW = InMemoryPorts.NOW;

    private final InMemoryPorts ports = new InMemoryPorts();
    private final SweepTimeoutsUseCase sweeper = new SweepTimeoutsUseCase(ports.transferRepository,
            ports.outboxRepository, ports.saga, ports.transactions, ports.ids, ports.clock, SagaTimeouts.SPEC);
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    private Transfer stored(Transfer transfer) {
        ports.transfers.put(transfer.transferId(), transfer);
        return transfer;
    }

    private Transfer pendingSentAt(Instant at) {
        TransferRequest request = new TransferRequest(sender, recipient, AMOUNT, Currency.COP, null, UUID.randomUUID(),
                "1".repeat(64));
        Transfer transfer = Transfer.create(request, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), at);
        ports.saga.sendCommand(transfer, at);
        return transfer;
    }

    private Transfer reload(Transfer transfer) {
        return ports.transfers.get(transfer.transferId());
    }

    private void clearOutbox() {
        ports.outbox.removeIf(m -> m.envelope().type() != MessageType.DEBIT_WALLET
                && m.envelope().type() != MessageType.REVERSE_DEBIT);
    }

    @Test
    void aPendingTransferWithoutReplyFor30sRepublishesItsOriginalDebit_rule8_F4_10() {
        Transfer transfer = stored(pendingSentAt(NOW.minusSeconds(30)));

        assertThat(sweeper.sweep(10)).isEqualTo(1);

        assertThat(ports.republished).containsExactly(transfer.lastCommandId());
        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(after.lastCommandAt()).isEqualTo(NOW);
        assertThat(after.lastCommandId()).isEqualTo(transfer.lastCommandId());
        assertThat(ports.written(MessageType.DEBIT_WALLET)).hasSize(1);
        assertThat(ports.written(MessageType.REVERSE_DEBIT)).isEmpty();
    }

    @Test
    void aPendingTransferStillInsideItsWindowIsLeftAlone() {
        stored(pendingSentAt(NOW.minusSeconds(29)));

        assertThat(sweeper.sweep(10)).isZero();
        assertThat(ports.republished).isEmpty();
    }

    @Test
    void aCreditWithoutReplyFor10sSendsTheNextAttempt_rule5() {
        Transfer transfer = stored(pendingSentAt(NOW.minusSeconds(60)).debited(UUID.randomUUID(), NOW.minusSeconds(10)));
        clearOutbox();

        sweeper.sweep(10);

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.DEBITED);
        assertThat(after.creditAttempts()).isEqualTo(2);
        assertThat(after.lastCommandId()).isNotEqualTo(transfer.lastCommandId());
        CreditWallet credit = ports.only(CreditWallet.class);
        assertThat(credit).isEqualTo(new CreditWallet(transfer.transferId(), recipient, AMOUNT, Currency.COP, 2));
        assertThat(ports.envelopeOf(credit).messageId()).isEqualTo(after.lastCommandId());
        AttemptRecorded timeout = ports.only(AttemptRecorded.class);
        assertThat(timeout.stage()).isEqualTo(AttemptStage.CREDIT);
        assertThat(timeout.outcome()).isEqualTo(AttemptOutcome.FAILED);
        assertThat(timeout.reason()).isEqualTo("CREDIT_TIMEOUT");
        assertThat(timeout.userId()).isEqualTo(recipient);
    }

    @Test
    void afterTheThirdTimeoutTheTransferIsCompensated_rule5_F4_3() {
        Transfer transfer = stored(pendingSentAt(NOW.minusSeconds(60)).debited(UUID.randomUUID(), NOW.minusSeconds(50))
                .creditRetried(UUID.randomUUID(), NOW.minusSeconds(40))
                .creditRetried(UUID.randomUUID(), NOW.minusSeconds(10)));
        clearOutbox();

        sweeper.sweep(10);

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(after.failureReason()).isEqualTo(FailureReason.CREDIT_TIMEOUT);
        ReverseDebit reverse = ports.only(ReverseDebit.class);
        assertThat(reverse).isEqualTo(new ReverseDebit(transfer.transferId(), sender, AMOUNT, Currency.COP,
                ReverseReason.CREDIT_TIMEOUT));
        assertThat(ports.envelopeOf(reverse).messageId()).isEqualTo(after.lastCommandId());
        assertThat(ports.written(MessageType.CREDIT_WALLET)).isEmpty();
        assertThat(ports.only(AttemptRecorded.class).reason()).isEqualTo("CREDIT_TIMEOUT");
    }

    @Test
    void theWholeTimeoutPathRetriesTwiceThenCompensates_F4_11() {
        Transfer transfer = stored(pendingSentAt(NOW.minusSeconds(60)).debited(UUID.randomUUID(), NOW.minusSeconds(10)));

        sweeper.sweep(10);
        ports.now = NOW.plusSeconds(10);
        sweeper.sweep(10);
        ports.now = NOW.plusSeconds(20);
        sweeper.sweep(10);

        assertThat(reload(transfer).status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(ports.written(MessageType.CREDIT_WALLET)).extracting(p -> ((CreditWallet) p).attempt())
                .containsExactly(2, 3);
        assertThat(ports.written(MessageType.REVERSE_DEBIT)).hasSize(1);
        assertThat(ports.written(MessageType.ATTEMPT_RECORDED)).hasSize(3);
    }

    @Test
    void aReverseWithoutReplyFor30sIsRepublishedForever_rule7_F4_13() {
        Transfer transfer = stored(pendingSentAt(NOW.minusSeconds(90)).debited(UUID.randomUUID(), NOW.minusSeconds(80))
                .compensating(FailureReason.RECIPIENT_WALLET_NOT_FOUND, UUID.randomUUID(), NOW.minusSeconds(30)));
        ports.saga.sendCommand(transfer, NOW.minusSeconds(30));

        sweeper.sweep(10);
        ports.now = NOW.plusSeconds(30);
        sweeper.sweep(10);

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(after.lastCommandId()).isEqualTo(transfer.lastCommandId());
        assertThat(after.lastCommandAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(ports.republished).containsExactly(transfer.lastCommandId());
        assertThat(ports.written(MessageType.REVERSE_DEBIT)).hasSize(1);
    }

    @Test
    void terminalAndFreshTransfersAreNeverTouched() {
        stored(pendingSentAt(NOW.minusSeconds(90)).debited(UUID.randomUUID(), NOW.minusSeconds(80))
                .completed(NOW.minusSeconds(70)));
        stored(pendingSentAt(NOW.minusSeconds(90)).rejected(FailureReason.INSUFFICIENT_FUNDS, NOW.minusSeconds(80)));
        stored(pendingSentAt(NOW.minusSeconds(60)).debited(UUID.randomUUID(), NOW.minusSeconds(9)));
        int before = ports.outbox.size();

        assertThat(sweeper.sweep(10)).isZero();
        assertThat(ports.outbox).hasSize(before);
    }

    @Test
    void aResultThatArrivedAfterTheScanWins() {
        Transfer transfer = stored(pendingSentAt(NOW.minusSeconds(60)).debited(UUID.randomUUID(), NOW.minusSeconds(10)));
        SweepTimeoutsUseCase racing = new SweepTimeoutsUseCase(new RacingRepository(transfer), ports.outboxRepository,
                ports.saga, ports.transactions, ports.ids, ports.clock, SagaTimeouts.SPEC);
        clearOutbox();

        assertThat(racing.sweep(10)).isZero();
        assertThat(ports.written(MessageType.CREDIT_WALLET)).isEmpty();
        assertThat(ports.written(MessageType.ATTEMPT_RECORDED)).isEmpty();
        assertThat(reload(transfer)).isEqualTo(transfer);
    }

    @Test
    void oneFailingTransferDoesNotStopTheOthers() {
        Transfer lost = stored(Transfer.create(new TransferRequest(sender, recipient, AMOUNT, Currency.COP, null,
                UUID.randomUUID(), "2".repeat(64)), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                NOW.minusSeconds(60)));
        Transfer fine = stored(pendingSentAt(NOW.minusSeconds(31)));

        assertThat(sweeper.sweep(10)).isEqualTo(1);

        assertThat(reload(lost)).isEqualTo(lost);
        assertThat(ports.republished).containsExactly(fine.lastCommandId());
    }

    @Test
    void theBatchSizeAndTheTimeoutsMustBePositive() {
        assertThatThrownBy(() -> sweeper.sweep(0)).isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> new SagaTimeouts(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> new SagaTimeouts(null, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .hasMessageContaining("pending");
        assertThat(SagaTimeouts.SPEC.credit()).isEqualTo(Duration.ofSeconds(10));
    }

    /** findDue still sees the transfer as DEBITED, but under the lock WalletCredited already completed it. */
    private final class RacingRepository extends DelegatingRepository {

        private final Transfer completed;

        RacingRepository(Transfer debited) {
            super(ports.transferRepository);
            this.completed = debited.completed(NOW.minusSeconds(1));
        }

        @Override
        public java.util.Optional<Transfer> lockById(UUID transferId) {
            return java.util.Optional.of(completed);
        }
    }

    private static class DelegatingRepository implements co.com.finova.transfer.model.transfer.gateways.TransferRepository {

        private final co.com.finova.transfer.model.transfer.gateways.TransferRepository delegate;

        DelegatingRepository(co.com.finova.transfer.model.transfer.gateways.TransferRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void insert(Transfer transfer) {
            delegate.insert(transfer);
        }

        @Override
        public void update(Transfer transfer) {
            delegate.update(transfer);
        }

        @Override
        public java.util.Optional<Transfer> findById(UUID transferId) {
            return delegate.findById(transferId);
        }

        @Override
        public java.util.Optional<Transfer> lockById(UUID transferId) {
            return delegate.lockById(transferId);
        }

        @Override
        public java.util.Optional<Transfer> findByIdempotencyKey(UUID senderUserId, UUID idempotencyKey) {
            return delegate.findByIdempotencyKey(senderUserId, idempotencyKey);
        }

        @Override
        public java.util.List<UUID> findDue(Instant pendingBefore, Instant debitedBefore, Instant compensatingBefore,
                                            int limit) {
            return delegate.findDue(pendingBefore, debitedBefore, compensatingBefore, limit);
        }
    }
}

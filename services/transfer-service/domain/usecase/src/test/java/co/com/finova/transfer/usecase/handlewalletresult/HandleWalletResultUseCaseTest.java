package co.com.finova.transfer.usecase.handlewalletresult;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptRecorded;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.CreditFailed;
import co.com.finova.transfer.model.message.payload.CreditFailureReason;
import co.com.finova.transfer.model.message.payload.CreditWallet;
import co.com.finova.transfer.model.message.payload.DebitRejected;
import co.com.finova.transfer.model.message.payload.DebitRejectionReason;
import co.com.finova.transfer.model.message.payload.DebitReversed;
import co.com.finova.transfer.model.message.payload.DebitWallet;
import co.com.finova.transfer.model.message.payload.MessagePayload;
import co.com.finova.transfer.model.message.payload.ReverseDebit;
import co.com.finova.transfer.model.message.payload.ReverseReason;
import co.com.finova.transfer.model.message.payload.TransferCompleted;
import co.com.finova.transfer.model.message.payload.TransferRejected;
import co.com.finova.transfer.model.message.payload.TransferReversed;
import co.com.finova.transfer.model.message.payload.WalletCredited;
import co.com.finova.transfer.model.message.payload.WalletDebited;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.SagaTelemetry;
import co.com.finova.transfer.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §4.6 rules 2, 3, 4, 6, 7 and 9, and the TRANSFER-stage audit records of §4.8. */
class HandleWalletResultUseCaseTest {

    private static final Money AMOUNT = Money.parse("150000.00");
    private static final Instant LATER = InMemoryPorts.NOW.plusSeconds(2);

    private final InMemoryPorts ports = new InMemoryPorts();
    private final HandleWalletResultUseCase useCase = new HandleWalletResultUseCase(ports.transferRepository,
            ports.processedRepository, ports.saga, ports.transactions, ports.ids, ports.clock, SagaTelemetry.NOOP);
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    private Transfer stored(Transfer transfer) {
        ports.transfers.put(transfer.transferId(), transfer);
        return transfer;
    }

    private Transfer pending() {
        TransferRequest request = new TransferRequest(sender, recipient, AMOUNT, Currency.COP, null, UUID.randomUUID(),
                "e".repeat(64));
        return stored(Transfer.create(request, UUID.randomUUID(), correlationId, UUID.randomUUID(),
                InMemoryPorts.NOW.minusSeconds(1)));
    }

    private Transfer debited() {
        return stored(pending().debited(UUID.randomUUID(), InMemoryPorts.NOW.minusSeconds(1)));
    }

    private Transfer compensating(FailureReason reason) {
        return stored(debited().compensating(reason, UUID.randomUUID(), InMemoryPorts.NOW.minusSeconds(1)));
    }

    private MessageEnvelope<MessagePayload> deliver(MessagePayload result, UUID transferId) {
        MessageEnvelope<MessagePayload> envelope = new MessageEnvelope<>(UUID.randomUUID(), result.type(),
                correlationId, transferId, "wallet-service", InMemoryPorts.NOW, result);
        ports.now = LATER;
        useCase.handle(envelope);
        return envelope;
    }

    private Transfer reload(Transfer transfer) {
        return ports.transfers.get(transfer.transferId());
    }

    @Test
    void walletDebitedSendsTheFirstCreditToTheRecipient_rule2() {
        Transfer transfer = pending();

        deliver(new WalletDebited(transfer.transferId(), walletId, sender, AMOUNT, 3), transfer.transferId());

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.DEBITED);
        assertThat(after.creditAttempts()).isEqualTo(1);
        assertThat(after.lastCommandAt()).isEqualTo(LATER);
        CreditWallet credit = ports.only(CreditWallet.class);
        assertThat(credit).isEqualTo(new CreditWallet(transfer.transferId(), recipient, AMOUNT, Currency.COP, 1));
        assertThat(ports.envelopeOf(credit).messageId()).isEqualTo(after.lastCommandId());
        assertThat(ports.envelopeOf(credit).correlationId()).isEqualTo(correlationId);
        assertThat(ports.written(MessageType.ATTEMPT_RECORDED)).isEmpty();
    }

    @Test
    void debitRejectedEndsRejectedAndTellsNotification_rule3_F4_2() {
        Transfer transfer = pending();

        deliver(new DebitRejected(transfer.transferId(), sender, DebitRejectionReason.INSUFFICIENT_FUNDS),
                transfer.transferId());

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(after.failureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(ports.only(TransferRejected.class)).isEqualTo(new TransferRejected(transfer.transferId(), sender,
                recipient, AMOUNT, Currency.COP, DebitRejectionReason.INSUFFICIENT_FUNDS));
        AttemptRecorded audit = ports.only(AttemptRecorded.class);
        assertThat(audit.stage()).isEqualTo(AttemptStage.TRANSFER);
        assertThat(audit.outcome()).isEqualTo(AttemptOutcome.REJECTED);
        assertThat(audit.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(audit.statusCode()).isNull();
    }

    @Test
    void walletCreditedCompletes_rule4_F4_1() {
        Transfer transfer = debited();

        deliver(new WalletCredited(transfer.transferId(), walletId, recipient, AMOUNT, 9), transfer.transferId());

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(after.completedAt()).isEqualTo(LATER);
        assertThat(ports.only(TransferCompleted.class)).isEqualTo(new TransferCompleted(transfer.transferId(), sender,
                recipient, AMOUNT, Currency.COP, LATER));
        assertThat(ports.only(AttemptRecorded.class).outcome()).isEqualTo(AttemptOutcome.COMPLETED);
    }

    @Test
    void aCreditFoundDuringCompensationCompletesTheTransfer_F4_5() {
        Transfer transfer = compensating(FailureReason.CREDIT_TIMEOUT);

        deliver(new WalletCredited(transfer.transferId(), walletId, recipient, AMOUNT, 9), transfer.transferId());

        assertThat(reload(transfer).status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(reload(transfer).failureReason()).isNull();
        assertThat(ports.written(MessageType.TRANSFER_COMPLETED)).hasSize(1);
    }

    @Test
    void aMissingRecipientWalletCompensatesAtOnce_rule6_F4_4() {
        Transfer transfer = debited();

        deliver(new CreditFailed(transfer.transferId(), recipient, 1, CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND),
                transfer.transferId());

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(after.failureReason()).isEqualTo(FailureReason.RECIPIENT_WALLET_NOT_FOUND);
        ReverseDebit reverse = ports.only(ReverseDebit.class);
        assertThat(reverse).isEqualTo(new ReverseDebit(transfer.transferId(), sender, AMOUNT, Currency.COP,
                ReverseReason.RECIPIENT_WALLET_NOT_FOUND));
        assertThat(ports.envelopeOf(reverse).messageId()).isEqualTo(after.lastCommandId());
        assertThat(ports.written(MessageType.TRANSFER_REVERSED)).isEmpty();
    }

    @Test
    void debitReversedEndsReversedWithItsReason_rule7_F4_3() {
        Transfer transfer = compensating(FailureReason.CREDIT_TIMEOUT);

        deliver(new DebitReversed(transfer.transferId(), walletId, sender, AMOUNT, 4), transfer.transferId());

        assertThat(reload(transfer).status()).isEqualTo(TransferStatus.REVERSED);
        assertThat(ports.only(TransferReversed.class).reason()).isEqualTo(ReverseReason.CREDIT_TIMEOUT);
        AttemptRecorded audit = ports.only(AttemptRecorded.class);
        assertThat(audit.outcome()).isEqualTo(AttemptOutcome.COMPENSATED);
        assertThat(audit.reason()).isEqualTo("CREDIT_TIMEOUT");
    }

    @Test
    void aLateCreditFailedAfterTheReversalIsIgnored_rule9_F4_6() {
        Transfer transfer = stored(compensating(FailureReason.CREDIT_TIMEOUT).reversed(InMemoryPorts.NOW));

        deliver(new CreditFailed(transfer.transferId(), recipient, 2, CreditFailureReason.TRANSFER_REVERSED),
                transfer.transferId());

        assertThat(reload(transfer)).isEqualTo(transfer);
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void creditFailedForTransferReversedIsIgnoredEvenWhileDebited_rule9() {
        Transfer transfer = debited();

        deliver(new CreditFailed(transfer.transferId(), recipient, 1, CreditFailureReason.TRANSFER_REVERSED),
                transfer.transferId());

        assertThat(reload(transfer)).isEqualTo(transfer);
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void aRepublishedResultAfterTheEndChangesNothing_rule9_F4_12() {
        Transfer transfer = stored(pending().rejected(FailureReason.INSUFFICIENT_FUNDS, InMemoryPorts.NOW));

        deliver(new DebitRejected(transfer.transferId(), sender, DebitRejectionReason.INSUFFICIENT_FUNDS),
                transfer.transferId());
        deliver(new WalletDebited(transfer.transferId(), walletId, sender, AMOUNT, 3), transfer.transferId());
        deliver(new WalletCredited(transfer.transferId(), walletId, recipient, AMOUNT, 3), transfer.transferId());
        deliver(new DebitReversed(transfer.transferId(), walletId, sender, AMOUNT, 3), transfer.transferId());

        assertThat(reload(transfer)).isEqualTo(transfer);
        assertThat(ports.outbox).isEmpty();
        assertThat(ports.processed).hasSize(4);
    }

    @Test
    void theSameMessageIdIsAppliedOnce() {
        Transfer transfer = pending();
        MessageEnvelope<MessagePayload> envelope = deliver(
                new WalletDebited(transfer.transferId(), walletId, sender, AMOUNT, 3), transfer.transferId());

        useCase.handle(envelope);

        assertThat(ports.written(MessageType.CREDIT_WALLET)).hasSize(1);
        assertThat(reload(transfer).creditAttempts()).isEqualTo(1);
    }

    @Test
    void aResultForAnUnknownTransferIsInvalidAndLeavesNothing() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> deliver(new WalletDebited(unknown, walletId, sender, AMOUNT, 3), unknown))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("unknown transfer");
        assertThat(ports.processed).isEmpty();
    }

    @Test
    void onlyWalletResultsAreHandled() {
        UUID transferId = UUID.randomUUID();
        DebitWallet command = new DebitWallet(transferId, sender, AMOUNT, Currency.COP);
        MessageEnvelope<DebitWallet> envelope = MessageEnvelope.create(UUID.randomUUID(), correlationId, transferId,
                InMemoryPorts.NOW, command);

        assertThatThrownBy(() -> useCase.handle(envelope)).isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("not a Wallet result");
    }
}

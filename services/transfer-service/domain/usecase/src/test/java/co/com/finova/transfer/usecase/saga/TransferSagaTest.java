package co.com.finova.transfer.usecase.saga;

import co.com.finova.transfer.model.common.Currency;
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
import co.com.finova.transfer.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** TransferSaga scenarios F4.1–F4.14 (spec §4.6). */
class TransferSagaTest {

    private static final Money AMOUNT = Money.parse("150000.00");
    private static final Instant LATER = InMemoryPorts.NOW.plusSeconds(2);

    private final InMemoryPorts ports = new InMemoryPorts();
    private final TransferSaga saga = new TransferSaga(ports.transferRepository, ports.processedRepository,
            ports.saga, ports.transactions, ports.ids, ports.clock);
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    private Transfer pending() {
        TransferRequest request = new TransferRequest(sender, recipient, AMOUNT, Currency.COP, null, UUID.randomUUID(),
                "e".repeat(64));
        Transfer transfer = Transfer.create(request, UUID.randomUUID(), correlationId, UUID.randomUUID(),
                InMemoryPorts.NOW.minusSeconds(1));
        ports.transfers.put(transfer.transferId(), transfer);
        return transfer;
    }

    private Transfer debited() {
        Transfer transfer = pending().debited(UUID.randomUUID(), InMemoryPorts.NOW.minusSeconds(1));
        ports.transfers.put(transfer.transferId(), transfer);
        return transfer;
    }

    private Transfer compensating(FailureReason reason) {
        Transfer transfer = debited().compensating(reason, UUID.randomUUID(), InMemoryPorts.NOW.minusSeconds(1));
        ports.transfers.put(transfer.transferId(), transfer);
        return transfer;
    }

    private Transfer reload(Transfer transfer) {
        return ports.transfers.get(transfer.transferId());
    }

    @Test
    void createTransferPersistsPendingAndSendsDebitWallet() {
        TransferRequest request = new TransferRequest(sender, recipient, AMOUNT, Currency.COP, null, UUID.randomUUID(),
                "e".repeat(64));

        saga.createTransfer(request, correlationId);

        Transfer after = ports.transfers.values().stream().findFirst().orElseThrow();
        assertThat(after.status()).isEqualTo(TransferStatus.PENDING);
        DebitWallet debit = ports.only(DebitWallet.class);
        assertThat(debit.userId()).isEqualTo(sender);
        assertThat(ports.only(AttemptRecorded.class).stage()).isEqualTo(AttemptStage.REQUEST);
        assertThat(ports.only(AttemptRecorded.class).outcome()).isEqualTo(AttemptOutcome.ACCEPTED);
    }

    @Test
    void walletDebitedSendsFirstCreditToRecipient_rule2() {
        Transfer transfer = pending();

        saga.onWalletDebited(new WalletDebited(transfer.transferId(), walletId, sender, AMOUNT, 3));

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.DEBITED);
        assertThat(after.creditAttempts()).isEqualTo(1);
        CreditWallet credit = ports.only(CreditWallet.class);
        assertThat(credit.userId()).isEqualTo(recipient);
        assertThat(credit.attempt()).isEqualTo(1);
    }

    @Test
    void debitRejectedEndsRejected_rule3() {
        Transfer transfer = pending();

        saga.onDebitRejected(new DebitRejected(transfer.transferId(), sender,
                DebitRejectionReason.INSUFFICIENT_FUNDS));

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(after.failureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(ports.only(TransferRejected.class).reason()).isEqualTo(DebitRejectionReason.INSUFFICIENT_FUNDS);
        assertThat(ports.only(AttemptRecorded.class).outcome()).isEqualTo(AttemptOutcome.REJECTED);
    }

    @Test
    void walletCreditedCompletes_rule4() {
        Transfer transfer = debited();

        saga.onWalletCredited(new WalletCredited(transfer.transferId(), walletId, recipient, AMOUNT, 9));

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(after.completedAt()).isEqualTo(LATER);
        assertThat(ports.only(TransferCompleted.class)).isNotNull();
        assertThat(ports.only(AttemptRecorded.class).outcome()).isEqualTo(AttemptOutcome.COMPLETED);
    }

    @Test
    void creditFoundDuringCompensationCompletes() {
        Transfer transfer = compensating(FailureReason.CREDIT_TIMEOUT);

        saga.onWalletCredited(new WalletCredited(transfer.transferId(), walletId, recipient, AMOUNT, 9));

        assertThat(reload(transfer).status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(reload(transfer).failureReason()).isNull();
    }

    @Test
    void missingRecipientWalletCompensatesAtOnce_rule6() {
        Transfer transfer = debited();

        saga.onCreditFailed(new CreditFailed(transfer.transferId(), recipient, 1,
                CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND));

        Transfer after = reload(transfer);
        assertThat(after.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(after.failureReason()).isEqualTo(FailureReason.RECIPIENT_WALLET_NOT_FOUND);
        ReverseDebit reverse = ports.only(ReverseDebit.class);
        assertThat(reverse.userId()).isEqualTo(sender);
        assertThat(reverse.reason()).isEqualTo(ReverseReason.RECIPIENT_WALLET_NOT_FOUND);
    }

    @Test
    void debitReversedEndsReversedWithReason_rule7() {
        Transfer transfer = compensating(FailureReason.CREDIT_TIMEOUT);

        saga.onDebitReversed(new DebitReversed(transfer.transferId(), walletId, sender, AMOUNT, 4));

        assertThat(reload(transfer).status()).isEqualTo(TransferStatus.REVERSED);
        assertThat(ports.only(TransferReversed.class).reason()).isEqualTo(ReverseReason.CREDIT_TIMEOUT);
        assertThat(ports.only(AttemptRecorded.class).outcome()).isEqualTo(AttemptOutcome.COMPENSATED);
    }

    @Test
    void lateCreditFailedAfterReversalIsIgnored_rule9() {
        Transfer transfer = stored(compensating(FailureReason.CREDIT_TIMEOUT).reversed(InMemoryPorts.NOW));

        saga.onCreditFailed(new CreditFailed(transfer.transferId(), recipient, 2,
                CreditFailureReason.TRANSFER_REVERSED));

        assertThat(reload(transfer)).isEqualTo(transfer);
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void creditFailedForTransferReversedIsIgnoredWhileDebited_rule9() {
        Transfer transfer = debited();

        saga.onCreditFailed(new CreditFailed(transfer.transferId(), recipient, 1,
                CreditFailureReason.TRANSFER_REVERSED));

        assertThat(reload(transfer)).isEqualTo(transfer);
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void republishedResultAfterEndChangesNothing_rule9() {
        Transfer transfer = stored(pending().rejected(FailureReason.INSUFFICIENT_FUNDS, InMemoryPorts.NOW));

        saga.onDebitRejected(new DebitRejected(transfer.transferId(), sender,
                DebitRejectionReason.INSUFFICIENT_FUNDS));
        saga.onWalletDebited(new WalletDebited(transfer.transferId(), walletId, sender, AMOUNT, 3));
        saga.onWalletCredited(new WalletCredited(transfer.transferId(), walletId, recipient, AMOUNT, 3));
        saga.onDebitReversed(new DebitReversed(transfer.transferId(), walletId, sender, AMOUNT, 3));

        assertThat(reload(transfer)).isEqualTo(transfer);
        assertThat(ports.outbox).isEmpty();
    }

    @Test
    void sameMessageIdIsAppliedOnce() {
        Transfer transfer = pending();
        WalletDebited event = new WalletDebited(transfer.transferId(), walletId, sender, AMOUNT, 3);

        saga.onWalletDebited(event);
        saga.onWalletDebited(event);

        assertThat(ports.written(MessageType.CREDIT_WALLET)).hasSize(1);
        assertThat(reload(transfer).creditAttempts()).isEqualTo(1);
    }

    @Test
    void creditRetriesUpToThreeThenCompensates() {
        Transfer transfer = debited();

        for (int i = 1; i <= 3; i++) {
            saga.onCreditFailed(new CreditFailed(transfer.transferId(), recipient, i,
                    CreditFailureReason.TRANSFER_REVERSED));
            assertThat(reload(transfer).status()).isEqualTo(TransferStatus.DEBITED);
            assertThat(reload(transfer).creditAttempts()).isEqualTo(i + 1);
        }

        saga.onCreditFailed(new CreditFailed(transfer.transferId(), recipient, 4,
                CreditFailureReason.TRANSFER_REVERSED));
        assertThat(reload(transfer).status()).isEqualTo(TransferStatus.COMPENSATING);
    }

    private Transfer stored(Transfer transfer) {
        ports.transfers.put(transfer.transferId(), transfer);
        return transfer;
    }
}

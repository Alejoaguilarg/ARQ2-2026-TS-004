package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.money.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.transfer.model.Fixtures.NOW;
import static co.com.finova.transfer.model.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §4.4: the Wallet results Transfer reads, the outcomes and audit attempts it writes. */
class PayloadsTest {

    private final UUID transferId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final Money amount = money("150000.00");

    @Test
    void walletResultsCarryTheirTransferAndType() {
        assertThat(new WalletDebited(transferId, walletId, userId, amount, 1).type()).isEqualTo(MessageType.WALLET_DEBITED);
        assertThat(new WalletCredited(transferId, walletId, userId, amount, 1).type())
                .isEqualTo(MessageType.WALLET_CREDITED);
        assertThat(new DebitReversed(transferId, walletId, userId, amount, 1).type())
                .isEqualTo(MessageType.DEBIT_REVERSED);
        assertThat(new DebitRejected(transferId, userId, DebitRejectionReason.INSUFFICIENT_FUNDS).type())
                .isEqualTo(MessageType.DEBIT_REJECTED);
        WalletResult failed = new CreditFailed(transferId, userId, 2, CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND);
        assertThat(failed.transferId()).isEqualTo(transferId);
        assertThat(((MessagePayload) failed).type()).isEqualTo(MessageType.CREDIT_FAILED);
    }

    @Test
    void walletResultsRejectInvalidFields() {
        assertThatThrownBy(() -> new WalletDebited(null, walletId, userId, amount, 1)).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new WalletDebited(transferId, null, userId, amount, 1)).hasMessageContaining("walletId");
        assertThatThrownBy(() -> new WalletCredited(transferId, walletId, null, amount, 1)).hasMessageContaining("userId");
        assertThatThrownBy(() -> new DebitReversed(transferId, walletId, userId, Money.ZERO, 1))
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> new WalletCredited(transferId, walletId, userId, amount, -1))
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new DebitRejected(transferId, userId, null)).hasMessageContaining("reason");
        assertThatThrownBy(() -> new CreditFailed(transferId, userId, 4, CreditFailureReason.TRANSFER_REVERSED))
                .hasMessageContaining("attempt");
        assertThatThrownBy(() -> new CreditFailed(transferId, userId, 1, null)).hasMessageContaining("reason");
    }

    @Test
    void outcomesCarryBothUsersForNotification() {
        TransferCompleted completed = new TransferCompleted(transferId, userId, otherId, amount, Currency.COP, NOW);
        TransferRejected rejected = new TransferRejected(transferId, userId, otherId, amount, Currency.COP,
                DebitRejectionReason.SENDER_WALLET_NOT_FOUND);
        TransferReversed reversed = new TransferReversed(transferId, userId, otherId, amount, Currency.COP,
                ReverseReason.CREDIT_TIMEOUT);

        assertThat(completed.type()).isEqualTo(MessageType.TRANSFER_COMPLETED);
        assertThat(rejected.type()).isEqualTo(MessageType.TRANSFER_REJECTED);
        assertThat(reversed.type()).isEqualTo(MessageType.TRANSFER_REVERSED);
        assertThat(reversed.recipientUserId()).isEqualTo(otherId);
    }

    @Test
    void outcomesRejectMissingFields() {
        assertThatThrownBy(() -> new TransferCompleted(transferId, null, otherId, amount, Currency.COP, NOW))
                .hasMessageContaining("senderUserId");
        assertThatThrownBy(() -> new TransferCompleted(transferId, userId, otherId, amount, Currency.COP, null))
                .hasMessageContaining("completedAt");
        assertThatThrownBy(() -> new TransferRejected(transferId, userId, null, amount, Currency.COP,
                DebitRejectionReason.INSUFFICIENT_FUNDS)).hasMessageContaining("recipientUserId");
        assertThatThrownBy(() -> new TransferRejected(transferId, userId, otherId, amount, Currency.COP, null))
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> new TransferReversed(null, userId, otherId, amount, Currency.COP,
                ReverseReason.CREDIT_TIMEOUT)).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new TransferReversed(transferId, userId, otherId, Money.ZERO, Currency.COP,
                ReverseReason.CREDIT_TIMEOUT)).hasMessageContaining("amount");
        assertThatThrownBy(() -> new TransferReversed(transferId, userId, otherId, amount, null,
                ReverseReason.CREDIT_TIMEOUT)).hasMessageContaining("currency");
    }

    @Test
    void anAttemptFromTransferHasSourceTransfer_s4_8() {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        AttemptRecorded attempt = AttemptRecorded.fromTransfer(eventId, AttemptStage.REQUEST, AttemptOutcome.DUPLICATE,
                "DUPLICATE_TRANSFER", transferId, userId, "key", amount, 202, correlationId, NOW);

        assertThat(attempt.source()).isEqualTo(AttemptSource.TRANSFER);
        assertThat(attempt.reason()).isEqualTo("DUPLICATE_TRANSFER");
        assertThat(attempt.idempotencyKey()).isEqualTo("key");
        assertThat(attempt.statusCode()).isEqualTo(202);
        assertThat(attempt.type()).isEqualTo(MessageType.ATTEMPT_RECORDED);
        assertThatThrownBy(() -> AttemptRecorded.fromTransfer(eventId, AttemptStage.TRANSFER, null, null, transferId,
                userId, null, amount, null, correlationId, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> AttemptRecorded.fromTransfer(eventId, null, AttemptOutcome.COMPLETED, null,
                transferId, userId, null, amount, null, correlationId, NOW)).hasMessageContaining("stage");
        assertThatThrownBy(() -> AttemptRecorded.fromTransfer(eventId, AttemptStage.TRANSFER, AttemptOutcome.COMPLETED,
                null, transferId, userId, null, amount, null, null, NOW)).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> AttemptRecorded.fromTransfer(null, AttemptStage.TRANSFER, AttemptOutcome.COMPLETED,
                null, transferId, userId, null, amount, null, correlationId, NOW)).hasMessageContaining("eventId");
        assertThatThrownBy(() -> AttemptRecorded.fromTransfer(eventId, AttemptStage.TRANSFER, AttemptOutcome.COMPLETED,
                null, transferId, userId, null, amount, null, correlationId, null)).hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> new AttemptRecorded(eventId, null, AttemptStage.TRANSFER, AttemptOutcome.COMPLETED,
                null, null, null, null, null, null, correlationId, NOW)).hasMessageContaining("source");
    }
}

package co.com.finova.transfer.model.transfer;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.payload.DebitRejectionReason;
import co.com.finova.transfer.model.message.payload.ReverseReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static co.com.finova.transfer.model.Fixtures.NOW;
import static co.com.finova.transfer.model.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §2 TransferStatus and §4.6: every allowed transition, and every other one refused. */
class TransferTest {

    static final String HASH = "a".repeat(64);
    private static final Instant LATER = NOW.plusSeconds(5);

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID debitCommand = UUID.randomUUID();

    static TransferRequest request(UUID sender, UUID recipient) {
        return new TransferRequest(sender, recipient, money("150000.00"), Currency.COP, "Dinner", UUID.randomUUID(),
                HASH);
    }

    private Transfer pending() {
        return Transfer.create(request(sender, recipient), UUID.randomUUID(), UUID.randomUUID(), debitCommand, NOW);
    }

    @Test
    void aNewTransferIsPendingWithItsDebitAsLastCommand_rule1() {
        Transfer transfer = pending();

        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.creditAttempts()).isZero();
        assertThat(transfer.lastCommandId()).isEqualTo(debitCommand);
        assertThat(transfer.lastCommandAt()).isEqualTo(NOW);
        assertThat(transfer.createdAt()).isEqualTo(NOW);
        assertThat(transfer.updatedAt()).isEqualTo(NOW);
        assertThat(transfer.failureReason()).isNull();
        assertThat(transfer.completedAt()).isNull();
        assertThat(transfer.description()).isEqualTo("Dinner");
    }

    @Test
    void aTransferToOneselfIsRefused() {
        assertThatThrownBy(() -> Transfer.create(request(sender, sender), UUID.randomUUID(), UUID.randomUUID(),
                debitCommand, NOW)).isInstanceOf(SelfTransferNotAllowedException.class);
    }

    @Test
    void theHappyPathCompletes_F4_1() {
        UUID credit = UUID.randomUUID();

        Transfer debited = pending().debited(credit, LATER);
        Transfer completed = debited.completed(LATER.plusSeconds(1));

        assertThat(debited.status()).isEqualTo(TransferStatus.DEBITED);
        assertThat(debited.creditAttempts()).isEqualTo(1);
        assertThat(debited.lastCommandId()).isEqualTo(credit);
        assertThat(debited.lastCommandAt()).isEqualTo(LATER);
        assertThat(completed.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(LATER.plusSeconds(1));
        assertThat(completed.status().isTerminal()).isTrue();
    }

    @Test
    void aRejectedDebitEndsTheTransfer_rule3() {
        Transfer rejected = pending().rejected(FailureReason.of(DebitRejectionReason.INSUFFICIENT_FUNDS), LATER);

        assertThat(rejected.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(rejected.failureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(rejected.failureReason().toDebitRejectionReason()).isEqualTo(DebitRejectionReason.INSUFFICIENT_FUNDS);
        assertThat(rejected.updatedAt()).isEqualTo(LATER);
        assertThat(rejected.completedAt()).isNull();
        assertThatThrownBy(() -> pending().rejected(null, LATER)).hasMessageContaining("reason");
    }

    @Test
    void creditIsRetriedUpToThreeAttempts_rule5() {
        Transfer debited = pending().debited(UUID.randomUUID(), NOW);
        UUID second = UUID.randomUUID();

        Transfer retried = debited.creditRetried(second, LATER).creditRetried(UUID.randomUUID(), LATER);

        assertThat(debited.creditRetried(second, LATER).lastCommandId()).isEqualTo(second);
        assertThat(retried.creditAttempts()).isEqualTo(3);
        assertThat(retried.canRetryCredit()).isFalse();
        assertThatThrownBy(() -> retried.creditRetried(UUID.randomUUID(), LATER))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("3 CreditWallet");
    }

    @Test
    void aCompensationEndsReversedAndKeepsItsReason_rule7() {
        UUID reverse = UUID.randomUUID();
        Transfer compensating = pending().debited(UUID.randomUUID(), NOW)
                .compensating(FailureReason.of(ReverseReason.CREDIT_TIMEOUT), reverse, LATER);

        Transfer reversed = compensating.reversed(LATER.plusSeconds(1));

        assertThat(compensating.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(compensating.lastCommandId()).isEqualTo(reverse);
        assertThat(compensating.failureReason().toReverseReason()).isEqualTo(ReverseReason.CREDIT_TIMEOUT);
        assertThat(reversed.status()).isEqualTo(TransferStatus.REVERSED);
        assertThat(reversed.failureReason()).isEqualTo(FailureReason.CREDIT_TIMEOUT);
        assertThat(reversed.completedAt()).isNull();
    }

    @Test
    void aCreditFoundDuringCompensationCompletesAndClearsTheReason_F4_5() {
        Transfer completed = pending().debited(UUID.randomUUID(), NOW)
                .compensating(FailureReason.RECIPIENT_WALLET_NOT_FOUND, UUID.randomUUID(), LATER)
                .completed(LATER);

        assertThat(completed.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(completed.failureReason()).isNull();
    }

    @Test
    void onlyTheTwoCompensationReasonsCompensate() {
        Transfer debited = pending().debited(UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> debited.compensating(FailureReason.INSUFFICIENT_FUNDS, UUID.randomUUID(), LATER))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> FailureReason.INSUFFICIENT_FUNDS.toReverseReason())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aResentCommandMovesOnlyLastCommandAt_rule8() {
        Transfer resent = pending().commandResent(LATER);

        assertThat(resent.lastCommandId()).isEqualTo(debitCommand);
        assertThat(resent.lastCommandAt()).isEqualTo(LATER);
        assertThat(resent.status()).isEqualTo(TransferStatus.PENDING);
        assertThatThrownBy(() -> pending().debited(UUID.randomUUID(), NOW).commandResent(LATER))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void transitionsNotAllowedByTheCurrentStatusFail() {
        Transfer pending = pending();
        Transfer completed = pending.debited(UUID.randomUUID(), NOW).completed(LATER);

        assertThatThrownBy(() -> pending.completed(LATER)).isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("PENDING");
        assertThatThrownBy(() -> pending.reversed(LATER)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> pending.creditRetried(UUID.randomUUID(), LATER))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> pending.compensating(FailureReason.CREDIT_TIMEOUT, UUID.randomUUID(), LATER))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> completed.debited(UUID.randomUUID(), LATER))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> completed.rejected(FailureReason.INSUFFICIENT_FUNDS, LATER))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> completed.completed(LATER)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void onlyTheSenderAndTheRecipientSeeTheTransfer_s3_9() {
        Transfer transfer = pending();

        assertThat(transfer.isVisibleTo(sender)).isTrue();
        assertThat(transfer.isVisibleTo(recipient)).isTrue();
        assertThat(transfer.isVisibleTo(UUID.randomUUID())).isFalse();
    }

    @Test
    void terminalStatusesAreCompletedRejectedAndReversed() {
        assertThat(TransferStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TransferStatus.REJECTED.isTerminal()).isTrue();
        assertThat(TransferStatus.REVERSED.isTerminal()).isTrue();
        assertThat(TransferStatus.PENDING.isTerminal()).isFalse();
        assertThat(TransferStatus.DEBITED.isTerminal()).isFalse();
        assertThat(TransferStatus.COMPENSATING.isTerminal()).isFalse();
    }

    @Test
    void theRequestAndTheTransferValidateTheirFields() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> new TransferRequest(null, recipient, money("1.00"), Currency.COP, null, key, HASH))
                .hasMessageContaining("senderUserId");
        assertThatThrownBy(() -> new TransferRequest(sender, null, money("1.00"), Currency.COP, null, key, HASH))
                .hasMessageContaining("recipientUserId");
        assertThatThrownBy(() -> new TransferRequest(sender, recipient, money("0.00"), Currency.COP, null, key, HASH))
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> new TransferRequest(sender, recipient, null, Currency.COP, null, key, HASH))
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> new TransferRequest(sender, recipient, money("1.00"), null, null, key, HASH))
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> new TransferRequest(sender, recipient, money("1.00"), Currency.COP, "x".repeat(141),
                key, HASH)).hasMessageContaining("description");
        assertThatThrownBy(() -> new TransferRequest(sender, recipient, money("1.00"), Currency.COP, null, null, HASH))
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> new TransferRequest(sender, recipient, money("1.00"), Currency.COP, null, key, " "))
                .hasMessageContaining("requestHash");
        assertThatThrownBy(() -> Transfer.create(new TransferRequest(sender, recipient, money("1.00"), Currency.COP,
                null, key, "not-a-hash"), UUID.randomUUID(), UUID.randomUUID(), debitCommand, NOW))
                .hasMessageContaining("requestHash");
        assertThatThrownBy(() -> Transfer.create(null, UUID.randomUUID(), UUID.randomUUID(), debitCommand, NOW))
                .hasMessageContaining("request");
        assertThatThrownBy(() -> Transfer.create(request(sender, recipient), UUID.randomUUID(), null, debitCommand,
                NOW)).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> Transfer.create(request(sender, recipient), UUID.randomUUID(), UUID.randomUUID(),
                null, NOW)).hasMessageContaining("lastCommandId");
    }

    @Test
    void exceptionsExplainThemselves() {
        UUID id = UUID.randomUUID();
        assertThat(new TransferNotFoundException(id)).hasMessageContaining(id.toString());
        assertThat(new IdempotencyKeyReusedException()).hasMessageContaining("different request body");
        assertThat(new DuplicateIdempotencyKeyException(new RuntimeException("x"))).hasCauseInstanceOf(RuntimeException.class);
        assertThat(new SelfTransferNotAllowedException()).hasMessageContaining("differ");
    }
}

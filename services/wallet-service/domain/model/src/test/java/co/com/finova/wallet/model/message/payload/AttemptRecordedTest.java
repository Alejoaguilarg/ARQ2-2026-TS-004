package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.message.MessageType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.NOW;
import static co.com.finova.wallet.model.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptRecordedTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @Test
    void fromWalletSetsSourceWalletAndLeavesGatewayFieldsEmpty_s4_8() {
        AttemptRecorded attempt = AttemptRecorded.fromWallet(eventId, AttemptStage.DEBIT, AttemptOutcome.REJECTED,
                "INSUFFICIENT_FUNDS", transferId, userId, money("80000.00"), correlationId, NOW);

        assertThat(attempt.eventId()).isEqualTo(eventId);
        assertThat(attempt.source()).isEqualTo(AttemptSource.WALLET);
        assertThat(attempt.stage()).isEqualTo(AttemptStage.DEBIT);
        assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.REJECTED);
        assertThat(attempt.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(attempt.transferId()).isEqualTo(transferId);
        assertThat(attempt.userId()).isEqualTo(userId);
        assertThat(attempt.idempotencyKey()).isNull();
        assertThat(attempt.amount()).isEqualTo(money("80000.00"));
        assertThat(attempt.statusCode()).isNull();
        assertThat(attempt.correlationId()).isEqualTo(correlationId);
        assertThat(attempt.occurredAt()).isEqualTo(NOW);
        assertThat(attempt.type()).isEqualTo(MessageType.ATTEMPT_RECORDED);
    }

    @Test
    void optionalFieldsMayBeNull_s4_4() {
        AttemptRecorded attempt = new AttemptRecorded(eventId, AttemptSource.GATEWAY, AttemptStage.REQUEST,
                AttemptOutcome.REJECTED, null, null, null, null, null, null, correlationId, NOW);

        assertThat(attempt.reason()).isNull();
        assertThat(attempt.transferId()).isNull();
        assertThat(attempt.userId()).isNull();
        assertThat(attempt.amount()).isNull();
    }

    @Test
    void enumsHaveAllValuesOfS4_4() {
        assertThat(AttemptSource.values()).containsExactly(
                AttemptSource.GATEWAY, AttemptSource.TRANSFER, AttemptSource.WALLET);
        assertThat(AttemptStage.values()).containsExactly(AttemptStage.REQUEST, AttemptStage.DEBIT,
                AttemptStage.CREDIT, AttemptStage.REVERSAL, AttemptStage.TRANSFER);
        assertThat(AttemptOutcome.values()).containsExactly(AttemptOutcome.ACCEPTED, AttemptOutcome.REJECTED,
                AttemptOutcome.DUPLICATE, AttemptOutcome.FAILED, AttemptOutcome.COMPENSATED,
                AttemptOutcome.COMPLETED, AttemptOutcome.APPLIED);
    }

    @Test
    void rejectsMissingRequiredFields_s4_4() {
        AttemptStage stage = AttemptStage.CREDIT;
        AttemptOutcome outcome = AttemptOutcome.APPLIED;

        assertThatThrownBy(() -> AttemptRecorded.fromWallet(null, stage, outcome, null, transferId, userId,
                null, correlationId, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> new AttemptRecorded(eventId, null, stage, outcome, null, null, null, null,
                null, null, correlationId, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> AttemptRecorded.fromWallet(eventId, null, outcome, null, transferId, userId,
                null, correlationId, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("stage");
        assertThatThrownBy(() -> AttemptRecorded.fromWallet(eventId, stage, null, null, transferId, userId,
                null, correlationId, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> AttemptRecorded.fromWallet(eventId, stage, outcome, null, transferId, userId,
                null, null, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> AttemptRecorded.fromWallet(eventId, stage, outcome, null, transferId, userId,
                null, correlationId, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("occurredAt");
    }
}

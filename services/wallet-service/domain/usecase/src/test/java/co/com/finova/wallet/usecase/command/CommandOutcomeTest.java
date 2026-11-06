package co.com.finova.wallet.usecase.command;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.usecase.command.CommandOutcome.Settlement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a saga command did, for the listener's span (observability only; the spec does not define these values):
 * the result it emitted, what happened to transfer_settlements and whether processed_messages answered it.
 */
class CommandOutcomeTest {

    @Test
    void keepsTheResultTheSettlementAndTheReplayFlag() {
        CommandOutcome outcome = new CommandOutcome(MessageType.CREDIT_FAILED, Settlement.ALREADY_REVERSED, false);

        assertThat(outcome.result()).isEqualTo(MessageType.CREDIT_FAILED);
        assertThat(outcome.settlement()).isEqualTo(Settlement.ALREADY_REVERSED);
        assertThat(outcome.replayed()).isFalse();
    }

    @Test
    void requiresTheResult() {
        assertThatThrownBy(() -> new CommandOutcome(null, Settlement.NOT_APPLICABLE, false))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void requiresTheSettlement() {
        assertThatThrownBy(() -> new CommandOutcome(MessageType.WALLET_DEBITED, null, false))
                .isInstanceOf(ContractViolationException.class);
    }
}

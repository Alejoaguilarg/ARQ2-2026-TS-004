package co.com.finova.wallet.model.settlement;

import co.com.finova.wallet.model.common.ContractViolationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferSettlementTest {

    private final UUID transferId = UUID.randomUUID();

    @Test
    void creditedFactorySetsOutcome_s2() {
        TransferSettlement settlement = TransferSettlement.credited(transferId, NOW);

        assertThat(settlement.transferId()).isEqualTo(transferId);
        assertThat(settlement.outcome()).isEqualTo(SettlementOutcome.CREDITED);
        assertThat(settlement.settledAt()).isEqualTo(NOW);
    }

    @Test
    void reversedFactorySetsOutcome_s2() {
        assertThat(TransferSettlement.reversed(transferId, NOW).outcome()).isEqualTo(SettlementOutcome.REVERSED);
    }

    @Test
    void outcomesAreOnlyCreditedAndReversed_s2() {
        assertThat(SettlementOutcome.values()).containsExactly(SettlementOutcome.CREDITED, SettlementOutcome.REVERSED);
    }

    @Test
    void rejectsMissingRequiredFields_s2() {
        assertThatThrownBy(() -> new TransferSettlement(null, SettlementOutcome.CREDITED, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("transferId");
        assertThatThrownBy(() -> new TransferSettlement(transferId, null, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> new TransferSettlement(transferId, SettlementOutcome.REVERSED, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("settledAt");
    }
}

package co.com.finova.transfer.model.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyTest {

    @Test
    void onlyCopExists_s1_5() {
        assertThat(Currency.values()).containsExactly(Currency.COP);
    }

    @Test
    void fromCodeParsesCop_s1_5() {
        assertThat(Currency.fromCode("COP")).isEqualTo(Currency.COP);
    }

    @Test
    void fromCodeRejectsOtherOrMissingCodes_s1_5() {
        assertThatThrownBy(() -> Currency.fromCode("USD"))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("USD");
        assertThatThrownBy(() -> Currency.fromCode("cop")).isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> Currency.fromCode(null)).isInstanceOf(ContractViolationException.class);
    }
}

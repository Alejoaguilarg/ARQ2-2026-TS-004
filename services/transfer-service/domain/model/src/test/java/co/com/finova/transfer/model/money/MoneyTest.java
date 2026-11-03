package co.com.finova.transfer.model.money;

import co.com.finova.transfer.model.common.ContractViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parseAcceptsDecimalStringWithTwoDecimals_s1_5() {
        assertThat(Money.parse("150000.00")).hasToString("150000.00");
    }

    @Test
    void parseAcceptsZero_s2() {
        Money zero = Money.parse("0.00");

        assertThat(zero).isEqualTo(Money.ZERO);
        assertThat(zero.isPositive()).isFalse();
    }

    @Test
    void parseAcceptsSeventeenIntegerDigits_s2() {
        assertThat(Money.parse("99999999999999999.99")).hasToString("99999999999999999.99");
    }

    @ParameterizedTest
    @ValueSource(strings = {"150000", "150000.5", "150000.000", "-1.00", "+1.00", "1,00", ".50",
            "1.5e3", " 1.00", "1.00 ", "", "123456789012345678.00", "abc"})
    void parseRejectsStringsOutsideTheContractPattern_s1_5(String text) {
        assertThatThrownBy(() -> Money.parse(text))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void parseRejectsNull() {
        assertThatThrownBy(() -> Money.parse(null))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("amount is required");
    }

    @Test
    void ofNormalizesToTwoDecimals_s1_5() {
        assertThat(Money.of(new BigDecimal("10"))).hasToString("10.00");
        assertThat(Money.of(new BigDecimal("10.5"))).hasToString("10.50");
        assertThat(Money.of(new BigDecimal("10.100"))).hasToString("10.10");
    }

    @Test
    void ofRejectsMoreThanTwoSignificantDecimals_s1_5() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.001")))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("at most 2 decimals");
    }

    @Test
    void ofRejectsNegative_s2() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("-0.01")))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void ofAcceptsMaximumAndRejectsAboveNumeric19_2_s2() {
        assertThat(Money.of(new BigDecimal("99999999999999999.99"))).hasToString("99999999999999999.99");
        assertThatThrownBy(() -> Money.of(new BigDecimal("100000000000000000.00")))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("NUMERIC(19,2)");
    }

    @Test
    void ofRejectsNull() {
        assertThatThrownBy(() -> Money.of(null)).isInstanceOf(ContractViolationException.class);
    }

    @Test
    void plusAddsAmounts() {
        assertThat(Money.parse("100.50").plus(Money.parse("0.75"))).hasToString("101.25");
    }

    @Test
    void plusRejectsResultAboveNumeric19_2() {
        Money max = Money.parse("99999999999999999.99");

        assertThatThrownBy(() -> max.plus(Money.parse("0.01")))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void minusSubtractsDownToExactlyZero() {
        assertThat(Money.parse("100.00").minus(Money.parse("40.25"))).hasToString("59.75");
        assertThat(Money.parse("80.00").minus(Money.parse("80.00"))).isEqualTo(Money.ZERO);
    }

    @Test
    void minusRejectsNegativeResult_s2() {
        Money balance = Money.parse("50000.00");

        assertThatThrownBy(() -> balance.minus(Money.parse("80000.00")))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void arithmeticRejectsNullOperand() {
        Money one = Money.parse("1.00");

        assertThatThrownBy(() -> one.plus(null)).isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> one.minus(null)).isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> one.isGreaterOrEqual(null)).isInstanceOf(ContractViolationException.class);
    }

    @Test
    void isPositiveOnlyAboveZero_s1_5() {
        assertThat(Money.parse("0.01").isPositive()).isTrue();
        assertThat(Money.ZERO.isPositive()).isFalse();
    }

    @Test
    void isGreaterOrEqualComparesValues() {
        Money ten = Money.parse("10.00");

        assertThat(ten.isGreaterOrEqual(Money.parse("10.00"))).isTrue();
        assertThat(ten.isGreaterOrEqual(Money.parse("9.99"))).isTrue();
        assertThat(ten.isGreaterOrEqual(Money.parse("10.01"))).isFalse();
    }

    @Test
    void equalityAndOrderingIgnoreTheInputScale() {
        Money fromBigDecimal = Money.of(new BigDecimal("10"));
        Money parsed = Money.parse("10.00");

        assertThat(fromBigDecimal).isEqualTo(parsed).hasSameHashCodeAs(parsed);
        assertThat(parsed.hashCode()).isNotEqualTo(Money.parse("10.01").hashCode());
        assertThat(parsed).isNotEqualTo(Money.parse("10.01")).isNotEqualTo("10.00");
        assertThat(parsed.compareTo(Money.parse("10.01"))).isNegative();
        assertThat(parsed.compareTo(Money.parse("9.99"))).isPositive();
        assertThat(parsed.compareTo(fromBigDecimal)).isZero();
    }

    @Test
    void toBigDecimalAlwaysHasScaleTwo_s1_5() {
        assertThat(Money.of(new BigDecimal("7")).toBigDecimal())
                .isEqualByComparingTo("7")
                .hasScaleOf(2);
    }
}

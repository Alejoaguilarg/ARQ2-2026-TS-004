package co.com.finova.transfer.model.money;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.ContractViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Non-negative amount with exactly two decimals (spec §1.5, §2): NUMERIC(19,2) in the
 * database and a decimal string such as "150000.00" in JSON. Never a double or a float.
 * Amounts in commands and events must also be positive; that rule lives in each payload.
 */
public final class Money implements Comparable<Money> {

    private static final int SCALE = 2;
    private static final Pattern CONTRACT_FORMAT = Pattern.compile("^\\d{1,17}\\.\\d{2}$");
    private static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE));

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value;
    }

    /** Parses the contract format: exactly two decimals and up to 17 integer digits. */
    public static Money parse(String text) {
        Contract.requireNonNull(text, "amount");
        Contract.require(CONTRACT_FORMAT.matcher(text).matches(),
                "amount must match ^\\d{1,17}\\.\\d{2}$ but was '" + text + "'");
        return new Money(new BigDecimal(text));
    }

    /** Accepts any scale that is exactly representable with two decimals ("10" or "10.100", not "10.001"). */
    public static Money of(BigDecimal amount) {
        Contract.requireNonNull(amount, "amount");
        BigDecimal scaled;
        try {
            scaled = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ContractViolationException("amount must have at most 2 decimals: " + amount.toPlainString());
        }
        Contract.require(scaled.signum() >= 0, "amount must not be negative: " + scaled.toPlainString());
        Contract.require(scaled.compareTo(MAX) <= 0, "amount exceeds NUMERIC(19,2): " + scaled.toPlainString());
        return new Money(scaled);
    }

    public Money plus(Money other) {
        Contract.requireNonNull(other, "other");
        return of(value.add(other.value));
    }

    /** Fails when the result would be negative: a balance never goes below zero. */
    public Money minus(Money other) {
        Contract.requireNonNull(other, "other");
        return of(value.subtract(other.value));
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isGreaterOrEqual(Money other) {
        Contract.requireNonNull(other, "other");
        return value.compareTo(other.value) >= 0;
    }

    public BigDecimal toBigDecimal() {
        return value;
    }

    @Override
    public int compareTo(Money other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Money money && value.equals(money.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** The contract representation, e.g. "150000.00". */
    @Override
    public String toString() {
        return value.toPlainString();
    }
}

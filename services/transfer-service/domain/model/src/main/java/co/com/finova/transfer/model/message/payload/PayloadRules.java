package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.money.Money;

/** Rules shared by several payloads of spec §4.4. */
final class PayloadRules {

    private static final int MIN_ATTEMPT = 1;
    private static final int MAX_ATTEMPT = 3;

    private PayloadRules() {
    }

    /** Money in a command or event is always > 0 (spec §1.5). */
    static void requirePositiveAmount(Money amount) {
        Contract.requireNonNull(amount, "amount");
        Contract.require(amount.isPositive(), "amount must be > 0");
    }

    static void requireAttempt(int attempt) {
        Contract.require(attempt >= MIN_ATTEMPT && attempt <= MAX_ATTEMPT,
                "attempt must be between 1 and 3: " + attempt);
    }

    static void requireVersion(long version) {
        Contract.require(version >= 0, "version must be >= 0: " + version);
    }
}

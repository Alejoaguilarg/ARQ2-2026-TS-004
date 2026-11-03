package co.com.finova.transfer.usecase.sweeptimeouts;

import co.com.finova.transfer.model.common.Contract;

import java.time.Duration;

/**
 * How long a saga command waits for its reply (spec §4.6): pending = a DebitWallet before it is republished
 * (30 s, rule 8), credit = each CreditWallet attempt (10 s, rule 5), reverseResend = a ReverseDebit before it is
 * republished (30 s, rule 7).
 */
public record SagaTimeouts(Duration pending, Duration credit, Duration reverseResend) {

    public static final SagaTimeouts SPEC = new SagaTimeouts(Duration.ofSeconds(30), Duration.ofSeconds(10),
            Duration.ofSeconds(30));

    public SagaTimeouts {
        Contract.requireNonNull(pending, "pending");
        Contract.requireNonNull(credit, "credit");
        Contract.requireNonNull(reverseResend, "reverseResend");
        Contract.require(pending.isPositive() && credit.isPositive() && reverseResend.isPositive(),
                "saga timeouts must be > 0");
    }
}

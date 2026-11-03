package co.com.finova.transfer.model.outbox;

import co.com.finova.transfer.model.common.Contract;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * How long an outbox row waits before its next publish attempt (audit finding H1): initialDelay after the first
 * failure, doubled after each further failure, never more than maxDelay. There is no attempt limit: a row is
 * never dropped.
 */
public record OutboxRetryBackoff(Duration initialDelay, Duration maxDelay) {

    // 2^30 times any initial delay of at least 1 ns is far above any sensible maximum, and stays within a long.
    private static final int MAX_DOUBLINGS = 30;

    public OutboxRetryBackoff {
        Contract.requireNonNull(initialDelay, "initialDelay");
        Contract.requireNonNull(maxDelay, "maxDelay");
        Contract.require(initialDelay.isPositive(), "initialDelay must be > 0: " + initialDelay);
        Contract.require(maxDelay.compareTo(initialDelay) >= 0,
                "maxDelay " + maxDelay + " must be >= initialDelay " + initialDelay);
    }

    /** The wait after the given number of failed attempts (1 = the first failure). */
    public Duration delayAfter(int failedAttempts) {
        Contract.require(failedAttempts >= 1, "failedAttempts must be >= 1: " + failedAttempts);
        int doublings = Math.min(failedAttempts - 1, MAX_DOUBLINGS);
        return Collections.min(List.of(initialDelay.multipliedBy(1L << doublings), maxDelay));
    }
}

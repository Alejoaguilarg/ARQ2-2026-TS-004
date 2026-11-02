package co.com.finova.wallet.rabbitmqconsumer;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.ledger.DebitMismatchException;
import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;

import java.util.List;
import java.util.Map;

/**
 * Decides what to do with a failed delivery (spec §4.2, §4.5).
 * <ul>
 *   <li>Invalid message (contract violation, a command inconsistent with the ledger): straight to the DLQ,
 *       because retrying it can never succeed.</li>
 *   <li>Technical error: retried through the .retry queue until the main queue has rejected the message
 *       maxRetries times (limit 3 = 4 deliveries). Only the x-death entry of the main queue with reason
 *       "rejected" counts; the "expired" entry of the .retry queue is ignored.</li>
 * </ul>
 */
public class RetryPolicy {

    public enum Decision { RETRY, DEAD_LETTER }

    private static final String REJECTED = "rejected";

    private final int maxRetries;

    public RetryPolicy(int maxRetries) {
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("maxRetries must be > 0: " + maxRetries);
        }
        this.maxRetries = maxRetries;
    }

    public Decision decide(Throwable failure, String queue, List<Map<String, ?>> xDeath) {
        if (isInvalidMessage(failure)) {
            return Decision.DEAD_LETTER;
        }
        return rejectedCount(queue, xDeath) >= maxRetries ? Decision.DEAD_LETTER : Decision.RETRY;
    }

    /** How many times the broker dead-lettered this message from the main queue because it was rejected. */
    public static long rejectedCount(String queue, List<Map<String, ?>> xDeath) {
        if (xDeath == null) {
            return 0;
        }
        return xDeath.stream()
                .filter(entry -> queue.equals(entry.get("queue")) && REJECTED.equals(entry.get("reason")))
                .mapToLong(entry -> entry.get("count") instanceof Number count ? count.longValue() : 0)
                .sum();
    }

    private static boolean isInvalidMessage(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ContractViolationException
                    || current instanceof DebitMismatchException
                    || current instanceof DuplicateLedgerEntryException) {
                return true;
            }
        }
        return false;
    }
}

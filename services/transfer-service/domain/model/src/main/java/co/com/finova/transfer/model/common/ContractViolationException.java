package co.com.finova.transfer.model.common;

/**
 * A value breaks a rule of the contract (docs/spec.md). The input itself is wrong,
 * so retrying it can never succeed.
 */
public class ContractViolationException extends RuntimeException {

    public ContractViolationException(String message) {
        super(message);
    }

    /** Keeps the original error (for example, a JSON parse error) for the logs. */
    public ContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}

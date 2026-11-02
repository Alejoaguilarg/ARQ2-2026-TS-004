package co.com.finova.wallet.model.common;

/**
 * A value breaks a rule of the contract (docs/spec.md). The input itself is wrong,
 * so retrying it can never succeed.
 */
public class ContractViolationException extends RuntimeException {

    public ContractViolationException(String message) {
        super(message);
    }
}

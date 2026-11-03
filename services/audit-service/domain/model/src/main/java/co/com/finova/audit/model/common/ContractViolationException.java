package co.com.finova.audit.model.common;

/** A domain object was built with data that breaks the contract (docs/spec.md). */
public class ContractViolationException extends RuntimeException {

    public ContractViolationException(String message) {
        super(message);
    }
}

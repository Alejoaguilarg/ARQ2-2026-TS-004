package co.com.finova.wallet.model.common;

/** Guard clauses used by the domain constructors; every failure is a {@link ContractViolationException}. */
public final class Contract {

    private Contract() {
    }

    public static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new ContractViolationException(field + " is required");
        }
    }

    public static void requireNotBlank(String value, String field) {
        requireNonNull(value, field);
        require(!value.isBlank(), field + " must not be blank");
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new ContractViolationException(message);
        }
    }
}

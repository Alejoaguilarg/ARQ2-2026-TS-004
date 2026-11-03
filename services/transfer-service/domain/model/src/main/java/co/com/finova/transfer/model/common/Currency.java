package co.com.finova.transfer.model.common;

/** ISO 4217 currency; the contract supports only COP (spec §1.5). */
public enum Currency {
    COP;

    public static Currency fromCode(String code) {
        for (Currency currency : values()) {
            if (currency.name().equals(code)) {
                return currency;
            }
        }
        throw new ContractViolationException("unsupported currency: " + code);
    }
}

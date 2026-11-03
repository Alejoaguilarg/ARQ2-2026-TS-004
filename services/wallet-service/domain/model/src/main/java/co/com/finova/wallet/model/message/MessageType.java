package co.com.finova.wallet.model.message;

import co.com.finova.wallet.model.common.ContractViolationException;

/**
 * Every message the Wallet consumes or publishes (spec §4.3), with its contract name (the envelope
 * {@code type}) and, for the ones the Wallet publishes, the exchange and routing key of §4.2.
 */
public enum MessageType {

    DEBIT_WALLET("DebitWallet", Kind.COMMAND, null),
    CREDIT_WALLET("CreditWallet", Kind.COMMAND, null),
    REVERSE_DEBIT("ReverseDebit", Kind.COMMAND, null),

    WALLET_DEBITED("WalletDebited", Kind.RESULT, new Destination("wallet.events", "wallet.debited")),
    DEBIT_REJECTED("DebitRejected", Kind.RESULT, new Destination("wallet.events", "wallet.debit-rejected")),
    WALLET_CREDITED("WalletCredited", Kind.RESULT, new Destination("wallet.events", "wallet.credited")),
    CREDIT_FAILED("CreditFailed", Kind.RESULT, new Destination("wallet.events", "wallet.credit-failed")),
    DEBIT_REVERSED("DebitReversed", Kind.RESULT, new Destination("wallet.events", "wallet.debit-reversed")),

    BALANCE_CHANGED("BalanceChanged", Kind.PROJECTION, new Destination("wallet.events", "wallet.balance-changed")),

    ATTEMPT_RECORDED("AttemptRecorded", Kind.AUDIT, new Destination("audit.events", "audit.attempt"));

    /** COMMAND: saga command from Transfer. RESULT: saga reply stored in processed_messages (§4.7). */
    public enum Kind { COMMAND, RESULT, PROJECTION, AUDIT }

    private final String contractName;
    private final Kind kind;
    private final Destination destination;

    MessageType(String contractName, Kind kind, Destination destination) {
        this.contractName = contractName;
        this.kind = kind;
        this.destination = destination;
    }

    public String contractName() {
        return contractName;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isResult() {
        return kind == Kind.RESULT;
    }

    public boolean isPublishedByWallet() {
        return destination != null;
    }

    public Destination publishDestination() {
        if (destination == null) {
            throw new ContractViolationException("Wallet never publishes " + contractName);
        }
        return destination;
    }

    public static MessageType fromContractName(String name) {
        for (MessageType type : values()) {
            if (type.contractName.equals(name)) {
                return type;
            }
        }
        throw new ContractViolationException("unknown message type: " + name);
    }
}

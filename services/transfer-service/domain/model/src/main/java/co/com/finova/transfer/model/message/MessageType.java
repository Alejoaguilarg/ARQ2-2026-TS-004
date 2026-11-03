package co.com.finova.transfer.model.message;

import co.com.finova.transfer.model.common.ContractViolationException;

/**
 * Every message Transfer publishes or consumes (spec §4.3), with its contract name (the envelope {@code type})
 * and, for the ones Transfer publishes, the exchange and routing key of §4.2.
 */
public enum MessageType {

    DEBIT_WALLET("DebitWallet", new Destination("wallet.commands", "debit")),
    CREDIT_WALLET("CreditWallet", new Destination("wallet.commands", "credit")),
    REVERSE_DEBIT("ReverseDebit", new Destination("wallet.commands", "reverse")),
    WALLET_DEBITED("WalletDebited", null),
    DEBIT_REJECTED("DebitRejected", null),
    WALLET_CREDITED("WalletCredited", null),
    CREDIT_FAILED("CreditFailed", null),
    DEBIT_REVERSED("DebitReversed", null),
    TRANSFER_COMPLETED("TransferCompleted", new Destination("transfer.events", "transfer.completed")),
    TRANSFER_REJECTED("TransferRejected", new Destination("transfer.events", "transfer.rejected")),
    TRANSFER_REVERSED("TransferReversed", new Destination("transfer.events", "transfer.reversed")),
    ATTEMPT_RECORDED("AttemptRecorded", new Destination("audit.events", "audit.attempt"));

    private final String contractName;
    private final Destination destination;

    MessageType(String contractName, Destination destination) {
        this.contractName = contractName;
        this.destination = destination;
    }

    public String contractName() {
        return contractName;
    }

    /** A Wallet result Transfer consumes from transfer.wallet-events.q. */
    public boolean isWalletResult() {
        return destination == null;
    }

    public Destination publishDestination() {
        if (destination == null) {
            throw new ContractViolationException("Transfer never publishes " + contractName);
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

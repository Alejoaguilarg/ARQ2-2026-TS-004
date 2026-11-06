package co.com.finova.transfer.model.transfer;

import co.com.finova.transfer.model.message.MessageType;

/**
 * What moved the saga (spec §4.6), as a fixed set of labels for the trace of each transition. Observability only:
 * the spec does not define these labels. The Wallet results use their contract name; the sweeper uses the name of
 * the timeout that fired.
 */
public enum SagaTrigger {
    CREATE_TRANSFER("CreateTransfer"),
    WALLET_DEBITED("WalletDebited"),
    DEBIT_REJECTED("DebitRejected"),
    WALLET_CREDITED("WalletCredited"),
    CREDIT_FAILED("CreditFailed"),
    DEBIT_REVERSED("DebitReversed"),
    /** Rule 8: a PENDING DebitWallet without a reply is republished. */
    PENDING_TIMEOUT("PENDING_TIMEOUT"),
    /** Rule 5: a CreditWallet without a reply, retried or (after the 3rd) compensated. */
    CREDIT_TIMEOUT("CREDIT_TIMEOUT"),
    /** Rule 7: the ReverseDebit of a COMPENSATING transfer is resent. */
    REVERSE_RESEND("REVERSE_RESEND");

    private final String label;

    SagaTrigger(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The trigger of a Wallet result consumed from transfer.wallet-events.q. */
    public static SagaTrigger ofResult(MessageType type) {
        return switch (type) {
            case WALLET_DEBITED -> WALLET_DEBITED;
            case DEBIT_REJECTED -> DEBIT_REJECTED;
            case WALLET_CREDITED -> WALLET_CREDITED;
            case CREDIT_FAILED -> CREDIT_FAILED;
            case DEBIT_REVERSED -> DEBIT_REVERSED;
            default -> throw new IllegalArgumentException(type.contractName() + " is not a Wallet result");
        };
    }
}

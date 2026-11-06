package co.com.finova.wallet.usecase.command;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.message.MessageType;

/**
 * What a saga command did, returned to the entry point so it can describe the delivery in its trace span. It is
 * observability only: nothing in the saga reads it, and the spec does not define these values.
 *
 * @param result     the Wallet result written to the outbox (§4.3)
 * @param settlement what happened to transfer_settlements (§4.7)
 * @param replayed   true when processed_messages answered a repeated command with its stored result (§4.7)
 */
public record CommandOutcome(MessageType result, Settlement settlement, boolean replayed) {

    /** The transfer_settlements claim, from the point of view of this command. */
    public enum Settlement {
        /** This command claimed the row: the credit or the reversal was applied. */
        CLAIMED,
        /** The row already said CREDITED: nothing was applied. */
        ALREADY_CREDITED,
        /** The row already said REVERSED: nothing was applied. */
        ALREADY_REVERSED,
        /** No claim was made: a debit, a credit without a recipient wallet, or a repeated command. */
        NOT_APPLICABLE
    }

    public CommandOutcome {
        Contract.requireNonNull(result, "result");
        Contract.requireNonNull(settlement, "settlement");
    }
}

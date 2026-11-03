package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.message.MessageType;

import java.util.UUID;

/** Result Wallet → Transfer (spec §4.4): business failure of a credit only; technical errors are retried. */
public record CreditFailed(UUID transferId, UUID userId, int attempt, CreditFailureReason reason)
        implements MessagePayload, WalletResult {

    public CreditFailed {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(userId, "userId");
        PayloadRules.requireAttempt(attempt);
        Contract.requireNonNull(reason, "reason");
    }

    @Override
    public MessageType type() {
        return MessageType.CREDIT_FAILED;
    }
}

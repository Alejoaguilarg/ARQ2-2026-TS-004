package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.message.MessageType;

import java.util.UUID;

/** Result Wallet → Transfer (spec §4.4): the debit was not applied. */
public record DebitRejected(UUID transferId, UUID userId, DebitRejectionReason reason) implements MessagePayload {

    public DebitRejected {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(userId, "userId");
        Contract.requireNonNull(reason, "reason");
    }

    @Override
    public MessageType type() {
        return MessageType.DEBIT_REJECTED;
    }
}

package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.money.Money;

import java.util.UUID;

/** Outcome Transfer → Notification (spec §4.4): the debit was reversed and the sender got the money back. */
public record TransferReversed(UUID transferId, UUID senderUserId, UUID recipientUserId, Money amount,
                               Currency currency, ReverseReason reason) implements MessagePayload {

    public TransferReversed {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(senderUserId, "senderUserId");
        Contract.requireNonNull(recipientUserId, "recipientUserId");
        PayloadRules.requirePositiveAmount(amount);
        Contract.requireNonNull(currency, "currency");
        Contract.requireNonNull(reason, "reason");
    }

    @Override
    public MessageType type() {
        return MessageType.TRANSFER_REVERSED;
    }
}

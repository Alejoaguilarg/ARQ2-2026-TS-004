package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.money.Money;

import java.util.UUID;

/** Outcome Transfer → Notification (spec §4.4): Wallet rejected the debit; nothing moved. */
public record TransferRejected(UUID transferId, UUID senderUserId, UUID recipientUserId, Money amount,
                               Currency currency, DebitRejectionReason reason) implements MessagePayload {

    public TransferRejected {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(senderUserId, "senderUserId");
        Contract.requireNonNull(recipientUserId, "recipientUserId");
        PayloadRules.requirePositiveAmount(amount);
        Contract.requireNonNull(currency, "currency");
        Contract.requireNonNull(reason, "reason");
    }

    @Override
    public MessageType type() {
        return MessageType.TRANSFER_REJECTED;
    }
}

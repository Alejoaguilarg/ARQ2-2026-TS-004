package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.money.Money;

import java.time.Instant;
import java.util.UUID;

/** Outcome Transfer → Notification (spec §4.4): the recipient was credited. */
public record TransferCompleted(UUID transferId, UUID senderUserId, UUID recipientUserId, Money amount,
                                Currency currency, Instant completedAt) implements MessagePayload {

    public TransferCompleted {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(senderUserId, "senderUserId");
        Contract.requireNonNull(recipientUserId, "recipientUserId");
        PayloadRules.requirePositiveAmount(amount);
        Contract.requireNonNull(currency, "currency");
        Contract.requireNonNull(completedAt, "completedAt");
    }

    @Override
    public MessageType type() {
        return MessageType.TRANSFER_COMPLETED;
    }
}

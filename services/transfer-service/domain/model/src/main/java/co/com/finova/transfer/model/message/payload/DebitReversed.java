package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.money.Money;

import java.util.UUID;

/** Result Wallet → Transfer (spec §4.4): the debit was reversed and the sender got the money back. */
public record DebitReversed(UUID transferId, UUID walletId, UUID userId, Money amount, long version)
        implements MessagePayload, WalletResult {

    public DebitReversed {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(walletId, "walletId");
        Contract.requireNonNull(userId, "userId");
        PayloadRules.requirePositiveAmount(amount);
        PayloadRules.requireVersion(version);
    }

    @Override
    public MessageType type() {
        return MessageType.DEBIT_REVERSED;
    }
}

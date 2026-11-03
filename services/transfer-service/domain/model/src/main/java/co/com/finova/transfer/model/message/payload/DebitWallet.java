package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.money.Money;

import java.util.UUID;

/** Command Transfer → Wallet (spec §4.4); userId is the sender. */
public record DebitWallet(UUID transferId, UUID userId, Money amount, Currency currency) implements MessagePayload {

    public DebitWallet {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(userId, "userId");
        PayloadRules.requirePositiveAmount(amount);
        Contract.requireNonNull(currency, "currency");
    }

    @Override
    public MessageType type() {
        return MessageType.DEBIT_WALLET;
    }
}

package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.money.Money;

import java.util.UUID;

/** Compensation command Transfer → Wallet (spec §4.4); userId is the sender. */
public record ReverseDebit(UUID transferId, UUID userId, Money amount, Currency currency, ReverseReason reason)
        implements MessagePayload {

    public ReverseDebit {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(userId, "userId");
        PayloadRules.requirePositiveAmount(amount);
        Contract.requireNonNull(currency, "currency");
        Contract.requireNonNull(reason, "reason");
    }

    @Override
    public MessageType type() {
        return MessageType.REVERSE_DEBIT;
    }
}

package co.com.finova.transfer.model.message.payload;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.model.money.Money;

import java.util.UUID;

/** Command Transfer → Wallet (spec §4.4); userId is the recipient, attempt goes from 1 to 3. */
public record CreditWallet(UUID transferId, UUID userId, Money amount, Currency currency, int attempt)
        implements MessagePayload {

    public CreditWallet {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(userId, "userId");
        PayloadRules.requirePositiveAmount(amount);
        Contract.requireNonNull(currency, "currency");
        PayloadRules.requireAttempt(attempt);
    }

    @Override
    public MessageType type() {
        return MessageType.CREDIT_WALLET;
    }
}

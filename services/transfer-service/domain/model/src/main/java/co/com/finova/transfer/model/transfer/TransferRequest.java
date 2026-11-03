package co.com.finova.transfer.model.transfer;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;

import java.util.UUID;

/**
 * A validated POST /transfers (spec §3.8): the body, the sender from X-User-Id, the Idempotency-Key and
 * requestHash = SHA-256 of the canonical body (§3.1.1). Sender ≠ recipient is checked by {@link Transfer#create}.
 */
public record TransferRequest(UUID senderUserId, UUID recipientUserId, Money amount, Currency currency,
                              String description, UUID idempotencyKey, String requestHash) {

    public TransferRequest {
        Contract.requireNonNull(senderUserId, "senderUserId");
        Contract.requireNonNull(recipientUserId, "recipientUserId");
        Contract.requireNonNull(amount, "amount");
        Contract.require(amount.isPositive(), "amount must be > 0");
        Contract.requireNonNull(currency, "currency");
        Contract.require(description == null || description.length() <= Transfer.MAX_DESCRIPTION_LENGTH,
                "description must have at most " + Transfer.MAX_DESCRIPTION_LENGTH + " characters");
        Contract.requireNonNull(idempotencyKey, "idempotencyKey");
        Contract.requireNotBlank(requestHash, "requestHash");
    }
}

package co.com.finova.transfer.model.transfer;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The transfer aggregate (spec §2 Transfer) and its state machine (§2 TransferStatus, §4.6). Each transition
 * returns a new value and fails with {@link IllegalTransitionException} when the current status does not allow
 * it; the saga checks the status first and ignores late or duplicate events (§4.6 rule 9).
 * lastCommandId is the outbox row of the last saga command, so the sweeper can republish that same message
 * (§4.6 rule 8); lastCommandAt is when it was sent.
 */
public record Transfer(UUID transferId, UUID senderUserId, UUID recipientUserId, Money amount, Currency currency,
                       String description, UUID idempotencyKey, String requestHash, TransferStatus status,
                       FailureReason failureReason, int creditAttempts, UUID correlationId, UUID lastCommandId,
                       Instant lastCommandAt, Instant createdAt, Instant updatedAt, Instant completedAt) {

    public static final int MAX_DESCRIPTION_LENGTH = 140;
    public static final int MAX_CREDIT_ATTEMPTS = 3;
    private static final Pattern SHA_256_HEX = Pattern.compile("^[a-f0-9]{64}$");

    public Transfer {
        Contract.requireNonNull(transferId, "transferId");
        Contract.requireNonNull(senderUserId, "senderUserId");
        Contract.requireNonNull(recipientUserId, "recipientUserId");
        Contract.requireNonNull(amount, "amount");
        Contract.require(amount.isPositive(), "amount must be > 0");
        Contract.requireNonNull(currency, "currency");
        Contract.require(description == null || description.length() <= MAX_DESCRIPTION_LENGTH,
                "description must have at most " + MAX_DESCRIPTION_LENGTH + " characters");
        Contract.requireNonNull(idempotencyKey, "idempotencyKey");
        Contract.requireNonNull(requestHash, "requestHash");
        Contract.require(SHA_256_HEX.matcher(requestHash).matches(), "requestHash must be a SHA-256 hex digest");
        Contract.requireNonNull(status, "status");
        Contract.require(creditAttempts >= 0 && creditAttempts <= MAX_CREDIT_ATTEMPTS,
                "creditAttempts must be between 0 and 3: " + creditAttempts);
        Contract.requireNonNull(correlationId, "correlationId");
        Contract.requireNonNull(lastCommandId, "lastCommandId");
        Contract.requireNonNull(lastCommandAt, "lastCommandAt");
        Contract.requireNonNull(createdAt, "createdAt");
        Contract.requireNonNull(updatedAt, "updatedAt");
    }

    /** §4.6 rule 1: a new transfer is PENDING and its first command is the DebitWallet with debitCommandId. */
    public static Transfer create(TransferRequest request, UUID transferId, UUID correlationId, UUID debitCommandId,
                                  Instant now) {
        Contract.requireNonNull(request, "request");
        if (request.senderUserId().equals(request.recipientUserId())) {
            throw new SelfTransferNotAllowedException();
        }
        return new Transfer(transferId, request.senderUserId(), request.recipientUserId(), request.amount(),
                request.currency(), request.description(), request.idempotencyKey(), request.requestHash(),
                TransferStatus.PENDING, null, 0, correlationId, debitCommandId, now, now, now, null);
    }

    /** §4.6 rule 2: WalletDebited → DEBITED; the first CreditWallet (attempt 1) is creditCommandId. */
    public Transfer debited(UUID creditCommandId, Instant now) {
        require(TransferStatus.PENDING, "debited");
        return with(TransferStatus.DEBITED, null, 1, creditCommandId, now, now, null);
    }

    /** §4.6 rule 3: DebitRejected → REJECTED (terminal). */
    public Transfer rejected(FailureReason reason, Instant now) {
        require(TransferStatus.PENDING, "rejected");
        Contract.requireNonNull(reason, "reason");
        return with(TransferStatus.REJECTED, reason, creditAttempts, lastCommandId, lastCommandAt, now, null);
    }

    /** §4.6 rule 4: WalletCredited in DEBITED or COMPENSATING → COMPLETED (terminal); failureReason is cleared. */
    public Transfer completed(Instant now) {
        if (status != TransferStatus.DEBITED && status != TransferStatus.COMPENSATING) {
            throw new IllegalTransitionException(transferId, status, "completed");
        }
        return with(TransferStatus.COMPLETED, null, creditAttempts, lastCommandId, lastCommandAt, now, now);
    }

    /** §4.6 rule 5: no reply to the CreditWallet within 10 s → a new CreditWallet with attempt + 1. */
    public Transfer creditRetried(UUID creditCommandId, Instant now) {
        require(TransferStatus.DEBITED, "credit retried");
        Contract.require(canRetryCredit(), "transfer " + transferId + " already sent " + MAX_CREDIT_ATTEMPTS
                + " CreditWallet attempts");
        return with(TransferStatus.DEBITED, null, creditAttempts + 1, creditCommandId, now, now, null);
    }

    public boolean canRetryCredit() {
        return creditAttempts < MAX_CREDIT_ATTEMPTS;
    }

    /** §4.6 rules 5 and 6: DEBITED → COMPENSATING; reverseCommandId is the ReverseDebit sent now. */
    public Transfer compensating(FailureReason reason, UUID reverseCommandId, Instant now) {
        require(TransferStatus.DEBITED, "compensating");
        Contract.require(reason == FailureReason.CREDIT_TIMEOUT || reason == FailureReason.RECIPIENT_WALLET_NOT_FOUND,
                "a transfer is compensated only for CREDIT_TIMEOUT or RECIPIENT_WALLET_NOT_FOUND: " + reason);
        return with(TransferStatus.COMPENSATING, reason, creditAttempts, reverseCommandId, now, now, null);
    }

    /** §4.6 rule 7: DebitReversed → REVERSED (terminal); failureReason stays. */
    public Transfer reversed(Instant now) {
        require(TransferStatus.COMPENSATING, "reversed");
        return with(TransferStatus.REVERSED, failureReason, creditAttempts, lastCommandId, lastCommandAt, now, null);
    }

    /** §4.6 rules 7 and 8: the last command (DebitWallet or ReverseDebit) was republished now. */
    public Transfer commandResent(Instant now) {
        Contract.require(status == TransferStatus.PENDING || status == TransferStatus.COMPENSATING,
                "only a PENDING DebitWallet or a COMPENSATING ReverseDebit is republished, not " + status);
        return with(status, failureReason, creditAttempts, lastCommandId, now, now, null);
    }

    /** Sender or recipient: the only users who may see the transfer (spec §3.9). */
    public boolean isVisibleTo(UUID userId) {
        return senderUserId.equals(userId) || recipientUserId.equals(userId);
    }

    private void require(TransferStatus expected, String transition) {
        if (status != expected) {
            throw new IllegalTransitionException(transferId, status, transition);
        }
    }

    private Transfer with(TransferStatus newStatus, FailureReason newReason, int newCreditAttempts,
                          UUID newLastCommandId, Instant newLastCommandAt, Instant now, Instant newCompletedAt) {
        return new Transfer(transferId, senderUserId, recipientUserId, amount, currency, description, idempotencyKey,
                requestHash, newStatus, newReason, newCreditAttempts, correlationId, newLastCommandId,
                newLastCommandAt, createdAt, now, newCompletedAt);
    }
}

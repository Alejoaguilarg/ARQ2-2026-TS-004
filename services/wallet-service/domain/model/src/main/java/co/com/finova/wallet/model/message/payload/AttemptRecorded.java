package co.com.finova.wallet.model.message.payload;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit event (spec §4.4, §4.8). eventId equals the envelope messageId. reason, transferId, userId,
 * idempotencyKey, amount and statusCode are nullable.
 * statusCode is the contract field {@code httpStatus}; the messaging adapter maps the JSON name, because
 * the architecture rules forbid technology names in domain fields.
 */
public record AttemptRecorded(UUID eventId, AttemptSource source, AttemptStage stage, AttemptOutcome outcome,
                              String reason, UUID transferId, UUID userId, String idempotencyKey, Money amount,
                              Integer statusCode, UUID correlationId, Instant occurredAt)
        implements MessagePayload {

    public AttemptRecorded {
        Contract.requireNonNull(eventId, "eventId");
        Contract.requireNonNull(source, "source");
        Contract.requireNonNull(stage, "stage");
        Contract.requireNonNull(outcome, "outcome");
        Contract.requireNonNull(correlationId, "correlationId");
        Contract.requireNonNull(occurredAt, "occurredAt");
    }

    /** An attempt recorded by the Wallet: it has no idempotency key and no HTTP status (spec §4.8). */
    public static AttemptRecorded fromWallet(UUID eventId, AttemptStage stage, AttemptOutcome outcome, String reason,
                                             UUID transferId, UUID userId, Money amount, UUID correlationId,
                                             Instant occurredAt) {
        return new AttemptRecorded(eventId, AttemptSource.WALLET, stage, outcome, reason, transferId, userId,
                null, amount, null, correlationId, occurredAt);
    }

    @Override
    public MessageType type() {
        return MessageType.ATTEMPT_RECORDED;
    }
}

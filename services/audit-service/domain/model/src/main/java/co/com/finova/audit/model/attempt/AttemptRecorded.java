package co.com.finova.audit.model.attempt;

import co.com.finova.audit.model.common.Contract;
import co.com.finova.audit.model.common.Timestamps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One transfer attempt or outcome reported by the Gateway, Transfer or Wallet (spec §4.4, §4.8).
 * eventId equals the envelope messageId and is the key duplicates are detected on (F5.3).
 * reason, transferId, userId, idempotencyKey, amount and statusCode are nullable; userId is null when
 * the Gateway rejects an unauthenticated request.
 *
 * <p>statusCode is the contract field {@code httpStatus}; the messaging and REST adapters map the
 * name, because the architecture rules forbid technology names in domain fields.
 *
 * <p>The values are normalized to what audit_db stores, so a record hashes the same before and after
 * a round trip: amount without leading zeros ({@code "0150.00"} becomes {@code "150.00"}) and
 * occurredAt with microsecond precision.
 */
public record AttemptRecorded(UUID eventId, AttemptSource source, AttemptStage stage, AttemptOutcome outcome,
                              String reason, UUID transferId, UUID userId, String idempotencyKey, String amount,
                              Integer statusCode, UUID correlationId, Instant occurredAt) {

    /** Spec §4.4 components.schemas.Money: a decimal text with exactly two decimals. */
    private static final Pattern AMOUNT_FORMAT = Pattern.compile("^\\d{1,17}\\.\\d{2}$");
    private static final int MIN_STATUS_CODE = 100;
    private static final int MAX_STATUS_CODE = 599;

    public AttemptRecorded {
        Contract.requireNonNull(eventId, "eventId");
        Contract.requireNonNull(source, "source");
        Contract.requireNonNull(stage, "stage");
        Contract.requireNonNull(outcome, "outcome");
        Contract.requireNonNull(correlationId, "correlationId");
        Contract.requireNonNull(occurredAt, "occurredAt");
        if (amount != null) {
            Contract.require(
                    AMOUNT_FORMAT.matcher(amount).matches(),
                    "amount must be a decimal text with exactly 2 decimals: '" + amount + "'"
            );

            BigDecimal parsedAmount = new BigDecimal(amount);

            Contract.require(
                    parsedAmount.signum() > 0,
                    "amount must be greater than 0"
            );

            amount = parsedAmount.setScale(2).toPlainString();
        }
        if (statusCode != null) {
            Contract.require(statusCode >= MIN_STATUS_CODE && statusCode <= MAX_STATUS_CODE,
                    "httpStatus must be between 100 and 599: " + statusCode);
        }
        occurredAt = Timestamps.toStoredPrecision(occurredAt);
    }
}

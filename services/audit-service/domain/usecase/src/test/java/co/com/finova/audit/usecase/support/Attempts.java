package co.com.finova.audit.usecase.support;

import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;

import java.time.Instant;
import java.util.UUID;

/** Test data for AttemptRecorded. */
public final class Attempts {

    public static final Instant T0 = Instant.parse("2026-10-19T15:00:00Z");

    private Attempts() {
    }

    public static AttemptRecorded walletDebit(UUID eventId, UUID transferId, UUID userId, Instant occurredAt) {
        return new AttemptRecorded(eventId, AttemptSource.WALLET, AttemptStage.DEBIT, AttemptOutcome.APPLIED,
                null, transferId, userId, null, "150000.00", null, UUID.randomUUID(), occurredAt);
    }

    public static AttemptRecorded any() {
        return walletDebit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), T0);
    }

    public static AttemptRecorded withAmount(AttemptRecorded attempt, String amount) {
        return new AttemptRecorded(attempt.eventId(), attempt.source(), attempt.stage(), attempt.outcome(),
                attempt.reason(), attempt.transferId(), attempt.userId(), attempt.idempotencyKey(), amount,
                attempt.statusCode(), attempt.correlationId(), attempt.occurredAt());
    }
}

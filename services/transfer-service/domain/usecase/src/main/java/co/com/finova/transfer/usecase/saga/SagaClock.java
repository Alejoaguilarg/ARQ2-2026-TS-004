package co.com.finova.transfer.usecase.saga;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One clock read per transaction, truncated to the microseconds PostgreSQL stores: updated_at,
 * last_command_at and every occurredAt written in the transaction are exactly the same instant.
 */
public final class SagaClock {

    private SagaClock() {
    }

    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

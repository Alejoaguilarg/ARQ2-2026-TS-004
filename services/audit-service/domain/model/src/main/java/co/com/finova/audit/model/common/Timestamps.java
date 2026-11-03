package co.com.finova.audit.model.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Timestamps of the audit trail. They are kept with microsecond precision, the precision of a
 * PostgreSQL TIMESTAMPTZ, so that a record read back from audit_db hashes exactly as it did when it
 * was written. The canonical text always has six decimals and the UTC designator Z.
 */
public final class Timestamps {

    private static final DateTimeFormatter CANONICAL =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    public static Instant toStoredPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    public static String canonical(Instant instant) {
        return CANONICAL.format(instant);
    }
}

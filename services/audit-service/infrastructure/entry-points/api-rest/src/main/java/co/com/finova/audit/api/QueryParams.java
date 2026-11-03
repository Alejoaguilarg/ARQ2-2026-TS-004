package co.com.finova.audit.api;

import co.com.finova.audit.model.query.InvalidAuditQueryException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parses the query parameters of §3.10 and §3.11 as text, so that a bad value is answered as
 * 400 VALIDATION_ERROR with the field name (§3.2) instead of the framework's own error. A missing or
 * blank parameter is null.
 */
final class QueryParams {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private QueryParams() {
    }

    static UUID uuid(String field, String value) {
        if (isBlank(value)) {
            return null;
        }
        if (!UUID_FORMAT.matcher(value.trim()).matches()) {
            throw new InvalidAuditQueryException(field, "must be a UUID");
        }
        return UUID.fromString(value.trim());
    }

    /** RFC 3339 date-time with offset, for example 2026-10-19T15:00:00Z (§1.5). */
    static Instant dateTime(String field, String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidAuditQueryException(field, "must be an RFC 3339 date-time such as 2026-10-19T15:00:00Z");
        }
    }

    static Integer integer(String field, String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidAuditQueryException(field, "must be an integer");
        }
    }

    static Long longInteger(String field, String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidAuditQueryException(field, "must be an integer");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

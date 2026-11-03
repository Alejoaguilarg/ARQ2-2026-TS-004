package co.com.finova.audit.model.record;

import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.common.Timestamps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The hash chain of the audit trail (ADR 005, spec §2 AuditRecord, F5.2):
 * {@code hash = SHA-256(prevHash || canonicalForm(record without prevHash and hash))}, lowercase hex.
 * The first record links to {@link #GENESIS_HASH}.
 *
 * <p>Canonical form, so anyone can recompute a hash outside this service:
 * <ul>
 *   <li>one JSON object with every contract field of AuditRecord but prevHash and hash (14 keys), sorted
 *       by code point, no whitespace, null values written as {@code null};</li>
 *   <li>seq and httpStatus as JSON integers; every other value as a JSON string;</li>
 *   <li>UUIDs in lowercase 8-4-4-4-12 form; amount with exactly two decimals and no superfluous
 *       leading zeros ({@code "0.50"}, {@code "150.00"});</li>
 *   <li>occurredAt and recordedAt as {@code yyyy-MM-ddTHH:mm:ss.ffffffZ} (UTC, six decimals);</li>
 *   <li>strings escaped as JSON: quote, backslash and control characters only, everything else as is;</li>
 *   <li>prevHash (64 hex characters) and the canonical form are concatenated and hashed as UTF-8.</li>
 * </ul>
 */
public final class HashChain {

    public static final String GENESIS_HASH = "0".repeat(64);

    private static final String STATUS_CODE_KEY = "httpStatus";

    private HashChain() {
    }

    public static String hash(String prevHash, long seq, AttemptRecorded attempt, Instant recordedAt) {
        return sha256(prevHash + canonicalForm(seq, attempt, recordedAt));
    }

    public static String canonicalForm(long seq, AttemptRecorded attempt, Instant recordedAt) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("seq", Long.toString(seq));
        fields.put("eventId", text(attempt.eventId()));
        fields.put("source", text(attempt.source().name()));
        fields.put("stage", text(attempt.stage().name()));
        fields.put("outcome", text(attempt.outcome().name()));
        fields.put("reason", text(attempt.reason()));
        fields.put("transferId", text(attempt.transferId()));
        fields.put("userId", text(attempt.userId()));
        fields.put("idempotencyKey", text(attempt.idempotencyKey()));
        fields.put("amount", text(attempt.amount()));
        fields.put(STATUS_CODE_KEY, attempt.statusCode() == null ? "null" : attempt.statusCode().toString());
        fields.put("correlationId", text(attempt.correlationId()));
        fields.put("occurredAt", text(Timestamps.canonical(attempt.occurredAt())));
        fields.put("recordedAt", text(Timestamps.canonical(recordedAt)));

        StringBuilder form = new StringBuilder(512).append('{');
        fields.forEach((key, value) -> {
            if (form.length() > 1) {
                form.append(',');
            }
            form.append(text(key)).append(':').append(value);
        });
        return form.append('}').toString();
    }

    private static String text(UUID value) {
        return value == null ? "null" : text(value.toString());
    }

    private static String text(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}

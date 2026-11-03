package co.com.finova.audit.rabbitmqconsumer;

import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import co.com.finova.audit.model.common.ContractViolationException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads an AttemptRecorded from the body of a message of audit.attempts.q.
 *
 * <p>The contract is the envelope of spec §4.1 with the payload of §4.4. Because the Gateway, Transfer
 * and Wallet are written by different people, the reader also accepts, without changing the meaning:
 * <ul>
 *   <li>the bare payload without the envelope;</li>
 *   <li>eventId, transferId and correlationId missing from the payload but present in the envelope
 *       (eventId falls back to messageId, which the contract says is the same value);</li>
 *   <li>occurredAt as Jackson writes a java.time.Instant when WRITE_DATES_AS_TIMESTAMPS is on: decimal
 *       epoch seconds ({@code 1760886000.123456789}) or integer epoch milliseconds;</li>
 *   <li>amount as a JSON number with at most two decimals.</li>
 * </ul>
 * Anything else that breaks the contract is a {@link MalformedAttemptException}.
 */
@Component
public class AttemptRecordedReader {

    static final String TYPE = "AttemptRecorded";

    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public AttemptRecorded read(byte[] body) {
        JsonNode root = parse(body);
        JsonNode payload = root.path("payload");
        if (payload.isObject()) {
            String type = optionalText(root, "type");
            if (type != null && !TYPE.equals(type)) {
                throw new MalformedAttemptException("type is '" + type + "', expected " + TYPE);
            }
        } else {
            payload = root;
        }
        try {
            return new AttemptRecorded(
                    uuidOrEnvelope(payload, "eventId", root, "messageId"),
                    enumValue(payload, "source", AttemptSource.class),
                    enumValue(payload, "stage", AttemptStage.class),
                    enumValue(payload, "outcome", AttemptOutcome.class),
                    optionalText(payload, "reason"),
                    uuidOrEnvelope(payload, "transferId", root, "transferId"),
                    optionalUuid(payload, "userId"),
                    optionalText(payload, "idempotencyKey"),
                    amount(payload),
                    statusCode(payload),
                    uuidOrEnvelope(payload, "correlationId", root, "correlationId"),
                    instant(payload, "occurredAt"));
        } catch (ContractViolationException e) {
            throw new MalformedAttemptException(e.getMessage(), e);
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new MalformedAttemptException("body is not a JSON object");
            }
            return root;
        } catch (IOException e) {
            throw new MalformedAttemptException("body is not JSON: " + e.getMessage(), e);
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isValueNode()) {
            throw new MalformedAttemptException(field + " must be a text");
        }
        return value.asText();
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        String text = optionalText(node, field);
        if (text == null) {
            return null;
        }
        if (!UUID_FORMAT.matcher(text).matches()) {
            throw new MalformedAttemptException(field + " is not a UUID: '" + text + "'");
        }
        return UUID.fromString(text);
    }

    private static UUID uuidOrEnvelope(JsonNode payload, String field, JsonNode envelope, String envelopeField) {
        UUID value = optionalUuid(payload, field);
        return value != null || payload == envelope ? value : optionalUuid(envelope, envelopeField);
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) {
        String text = optionalText(node, field);
        if (text == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new MalformedAttemptException(field + " is '" + text + "', expected one of "
                    + Arrays.toString(type.getEnumConstants()), e);
        }
    }

    private static String amount(JsonNode node) {
        JsonNode value = node.get("amount");
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            try {
                return value.decimalValue().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
            } catch (ArithmeticException e) {
                throw new MalformedAttemptException("amount has more than 2 decimals: " + value, e);
            }
        }
        return optionalText(node, "amount");
    }

    private static Integer statusCode(JsonNode node) {
        JsonNode value = node.get("httpStatus");
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        if (value.isTextual() && value.asText().matches("\\d{3}")) {
            return Integer.valueOf(value.asText());
        }
        throw new MalformedAttemptException("httpStatus must be an integer: " + value);
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isTextual()) {
                return OffsetDateTime.parse(value.asText()).toInstant();
            }
            if (value.isIntegralNumber()) {
                return Instant.ofEpochMilli(value.longValue());
            }
            if (value.isNumber()) {
                BigDecimal seconds = value.decimalValue();
                long whole = seconds.setScale(0, RoundingMode.FLOOR).longValueExact();
                long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9)
                        .setScale(0, RoundingMode.DOWN).longValueExact();
                return Instant.ofEpochSecond(whole, nanos);
            }
        } catch (DateTimeException | ArithmeticException e) {
            throw new MalformedAttemptException(field + " is not a valid date-time: " + value, e);
        }
        throw new MalformedAttemptException(field + " must be an RFC 3339 date-time: " + value);
    }
}

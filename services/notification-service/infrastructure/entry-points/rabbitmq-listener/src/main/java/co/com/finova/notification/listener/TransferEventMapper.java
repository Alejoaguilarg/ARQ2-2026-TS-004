package co.com.finova.notification.listener;

import co.com.finova.notification.model.notification.TransferEventType;
import co.com.finova.notification.model.notification.TransferOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads the message body: the envelope of spec §4.1 with a TransferCompleted, TransferRejected or
 * TransferReversed payload (spec §4.4). Only the fields this service needs are validated.
 */
public class TransferEventMapper {

    private static final Pattern MONEY = Pattern.compile("^\\d{1,17}\\.\\d{2}$");

    private final ObjectMapper objectMapper;

    public TransferEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TransferOutcome toOutcome(byte[] body) {
        JsonNode envelope = parse(body);

        String type = requiredText(envelope, "type");
        TransferEventType eventType = TransferEventType.fromMessageType(type)
                .orElseThrow(() -> new InvalidMessageException("Unsupported message type: " + type));

        JsonNode payload = envelope.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new InvalidMessageException("payload must be a JSON object");
        }

        String amount = requiredText(payload, "amount");
        if (!MONEY.matcher(amount).matches()) {
            throw new InvalidMessageException("amount must be a decimal string with 2 decimals: " + amount);
        }
        String currency = requiredText(payload, "currency");
        if (!"COP".equals(currency)) {
            throw new InvalidMessageException("currency must be COP: " + currency);
        }
        String reason = eventType == TransferEventType.TRANSFER_COMPLETED ? null : requiredText(payload, "reason");

        return new TransferOutcome(
                eventType,
                requiredUuid(payload, "transferId"),
                requiredUuid(payload, "senderUserId"),
                requiredUuid(payload, "recipientUserId"),
                amount,
                currency,
                reason,
                optionalText(envelope, "correlationId"));
    }

    private JsonNode parse(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new InvalidMessageException("Body is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidMessageException("Body must be the JSON envelope of spec §4.1");
        }
        return root;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new InvalidMessageException("Missing required field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        String value = requiredText(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException(field + " is not a UUID: " + value, e);
        }
    }
}

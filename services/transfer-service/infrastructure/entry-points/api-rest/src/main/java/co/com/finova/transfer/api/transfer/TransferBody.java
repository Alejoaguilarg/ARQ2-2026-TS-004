package co.com.finova.transfer.api.transfer;

import co.com.finova.transfer.api.config.UuidFormat;
import co.com.finova.transfer.api.error.InvalidRequestException;
import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The body of POST /transfers (spec §3.8) checked field by field, and requestHash = SHA-256 of the canonical body
 * (§3.1.1): keys sorted, no whitespace, the same canonical form the Gateway hashes.
 */
final class TransferBody {

    private static final ObjectMapper CANONICAL_JSON =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Pattern AMOUNT = Pattern.compile("^\\d{1,17}\\.\\d{2}$");

    private TransferBody() {
    }

    static TransferRequest parse(byte[] body, UUID senderUserId, UUID idempotencyKey) {
        JsonNode root = readObject(body);
        Map<String, Object> errors = new LinkedHashMap<>();
        UUID recipient = recipient(root.get("recipientUserId"), errors);
        Money amount = amount(root.get("amount"), errors);
        Currency currency = currency(root.get("currency"), errors);
        String description = description(root.get("description"), errors);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        return new TransferRequest(senderUserId, recipient, amount, currency, description, idempotencyKey,
                requestHash(root));
    }

    private static JsonNode readObject(byte[] body) {
        try {
            JsonNode root = CANONICAL_JSON.readTree(body);
            if (root != null && root.isObject()) {
                return root;
            }
        } catch (IOException e) {
            // Answered below as an invalid body.
        }
        throw new InvalidRequestException(Map.of("body", "must be a JSON object"));
    }

    private static UUID recipient(JsonNode node, Map<String, Object> errors) {
        if (node == null || !node.isTextual() || !UuidFormat.isUuid(node.textValue())) {
            errors.put("recipientUserId", "is required and must be a UUID");
            return null;
        }
        return UUID.fromString(node.textValue());
    }

    private static Money amount(JsonNode node, Map<String, Object> errors) {
        if (node == null || !node.isTextual() || !AMOUNT.matcher(node.textValue()).matches()
                || new BigDecimal(node.textValue()).signum() <= 0) {
            errors.put("amount", "is required and must be a decimal string > 0 with 2 decimals, e.g. \"150000.00\"");
            return null;
        }
        return Money.parse(node.textValue());
    }

    private static Currency currency(JsonNode node, Map<String, Object> errors) {
        if (node == null || !node.isTextual() || !Currency.COP.name().equals(node.textValue())) {
            errors.put("currency", "is required and must be COP");
            return null;
        }
        return Currency.COP;
    }

    private static String description(JsonNode node, Map<String, Object> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.textValue().length() > Transfer.MAX_DESCRIPTION_LENGTH) {
            errors.put("description", "must be a string of at most " + Transfer.MAX_DESCRIPTION_LENGTH
                    + " characters");
            return null;
        }
        return node.textValue();
    }

    static String requestHash(JsonNode root) {
        try {
            Object canonical = CANONICAL_JSON.treeToValue(root, Object.class);
            byte[] bytes = CANONICAL_JSON.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash the request body", e);
        }
    }
}

package co.com.finova.wallet.messagejson;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.CreditFailed;
import co.com.finova.wallet.model.message.payload.CreditWallet;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitReversed;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.message.payload.ReverseDebit;
import co.com.finova.wallet.model.message.payload.WalletCredited;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.money.Money;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * JSON of the message contract: envelope of spec §4.1, payloads of §4.4, money as a text with two decimals
 * (§1.5) and RFC 3339 UTC timestamps. It writes the messages stored in wallet_db (outbox_messages.payload and
 * processed_messages.result_payload; the relay publishes that JSON as it is), reads the stored results back,
 * and reads the messages the Wallet consumes from RabbitMQ.
 */
@Component
public class MessageJsonMapper {

    private static final Map<MessageType, Class<? extends MessagePayload>> RESULT_CLASSES = Map.of(
            MessageType.WALLET_DEBITED, WalletDebited.class,
            MessageType.DEBIT_REJECTED, DebitRejected.class,
            MessageType.WALLET_CREDITED, WalletCredited.class,
            MessageType.CREDIT_FAILED, CreditFailed.class,
            MessageType.DEBIT_REVERSED, DebitReversed.class);

    /** The messages the Wallet consumes: saga commands (wallet.commands.q) and its own BalanceChanged. */
    private static final Map<MessageType, Class<? extends MessagePayload>> INBOUND_CLASSES = Map.of(
            MessageType.DEBIT_WALLET, DebitWallet.class,
            MessageType.CREDIT_WALLET, CreditWallet.class,
            MessageType.REVERSE_DEBIT, ReverseDebit.class,
            MessageType.BALANCE_CHANGED, BalanceChanged.class);

    private final ObjectMapper mapper;
    // Inbound messages: unknown fields are ignored, but every payload field must be present (S4).
    private final ObjectReader inboundReader;

    public MessageJsonMapper() {
        SimpleModule contract = new SimpleModule("finova-wallet-contract")
                .addSerializer(Money.class, new MoneySerializer())
                .addDeserializer(Money.class, new MoneyDeserializer())
                .addSerializer(MessageType.class, new MessageTypeSerializer())
                .addSerializer(AttemptRecorded.class, new AttemptRecordedSerializer());
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(contract)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.inboundReader = mapper.reader()
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    /**
     * A message consumed from RabbitMQ with its envelope (spec §4.1) and payload (§4.4). Any invalid input
     * fails with ContractViolationException: retrying it can never succeed, so it goes to the DLQ.
     */
    public MessageEnvelope<?> readInbound(String json) {
        try {
            JsonNode root = inboundReader.readTree(json);
            if (root == null || !root.isObject()) {
                throw new ContractViolationException("message must be a JSON object");
            }
            MessageType type = MessageType.fromContractName(requiredText(root, "type"));
            Class<? extends MessagePayload> payloadClass = INBOUND_CLASSES.get(type);
            if (payloadClass == null) {
                throw new ContractViolationException("the Wallet does not consume " + type.contractName());
            }
            JsonNode payloadNode = root.get("payload");
            if (payloadNode == null || !payloadNode.isObject()) {
                throw new ContractViolationException("payload must be a JSON object");
            }
            MessagePayload payload = inboundReader.forType(payloadClass).readValue(payloadNode);
            MessageEnvelope<MessagePayload> envelope = new MessageEnvelope<>(requiredUuid(root, "messageId"),
                    type, requiredUuid(root, "correlationId"), optionalUuid(root, "transferId"),
                    requiredText(root, "producer"), requiredInstant(root, "occurredAt"), payload);
            requireSameTransfer(envelope);
            return envelope;
        } catch (JsonProcessingException e) {
            throw new ContractViolationException("invalid message JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new ContractViolationException("unreadable message: " + e.getMessage(), e);
        }
    }

    /** S5: a command whose envelope transferId differs from its payload transferId is invalid. */
    private static void requireSameTransfer(MessageEnvelope<MessagePayload> envelope) {
        UUID payloadTransferId = switch (envelope.payload()) {
            case DebitWallet debit -> debit.transferId();
            case CreditWallet credit -> credit.transferId();
            case ReverseDebit reverse -> reverse.transferId();
            default -> null;
        };
        if (envelope.transferId() != null && payloadTransferId != null
                && !envelope.transferId().equals(payloadTransferId)) {
            throw new ContractViolationException("envelope transferId " + envelope.transferId()
                    + " does not match payload transferId " + payloadTransferId);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new ContractViolationException(field + " is required and must be a string");
        }
        return node.textValue();
    }

    private static UUID requiredUuid(JsonNode root, String field) {
        return parseUuid(field, requiredText(root, field));
    }

    private static UUID optionalUuid(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return requiredUuid(root, field);
    }

    private static UUID parseUuid(String field, String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new ContractViolationException(field + " must be a UUID but was '" + text + "'", e);
        }
    }

    private static Instant requiredInstant(JsonNode root, String field) {
        String text = requiredText(root, field);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new ContractViolationException(field + " must be an RFC 3339 timestamp but was '" + text + "'", e);
        }
    }

    /** An envelope or a payload as JSON text. */
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write " + value.getClass().getSimpleName() + " as JSON", e);
        }
    }

    /** A stored Wallet result (processed_messages.result_payload) of the given type. */
    public MessagePayload readResult(MessageType type, String json) {
        Class<? extends MessagePayload> target = RESULT_CLASSES.get(type);
        if (target == null) {
            throw new IllegalArgumentException(type.contractName() + " is not a Wallet result");
        }
        try {
            return mapper.readValue(json, target);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read stored " + type.contractName(), e);
        }
    }

    private static final class MoneySerializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.toString());
        }
    }

    private static final class MoneyDeserializer extends JsonDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // Spec §1.5: money travels as a JSON string ("150000.00"), never as a JSON number.
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw MismatchedInputException.from(parser, Money.class, "amount must be a JSON string");
            }
            return Money.parse(parser.getValueAsString());
        }
    }

    private static final class MessageTypeSerializer extends JsonSerializer<MessageType> {
        @Override
        public void serialize(MessageType value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.contractName());
        }
    }

    /** Spec §4.4 AttemptRecorded; the domain field statusCode is the contract field httpStatus. */
    private static final class AttemptRecordedSerializer extends JsonSerializer<AttemptRecorded> {
        @Override
        public void serialize(AttemptRecorded value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeStartObject();
            gen.writeStringField("eventId", value.eventId().toString());
            gen.writeStringField("source", value.source().name());
            gen.writeStringField("stage", value.stage().name());
            gen.writeStringField("outcome", value.outcome().name());
            gen.writeStringField("reason", value.reason());
            gen.writeStringField("transferId", text(value.transferId()));
            gen.writeStringField("userId", text(value.userId()));
            gen.writeStringField("idempotencyKey", value.idempotencyKey());
            gen.writeStringField("amount", value.amount() == null ? null : value.amount().toString());
            if (value.statusCode() == null) {
                gen.writeNullField("httpStatus");
            } else {
                gen.writeNumberField("httpStatus", value.statusCode());
            }
            gen.writeStringField("correlationId", value.correlationId().toString());
            gen.writeFieldName("occurredAt");
            serializers.defaultSerializeValue(value.occurredAt(), gen);
            gen.writeEndObject();
        }

        private static String text(UUID id) {
            return id == null ? null : id.toString();
        }
    }
}

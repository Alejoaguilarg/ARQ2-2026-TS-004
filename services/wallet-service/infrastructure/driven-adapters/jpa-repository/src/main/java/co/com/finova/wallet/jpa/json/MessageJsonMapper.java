package co.com.finova.wallet.jpa.json;

import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.CreditFailed;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitReversed;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.message.payload.WalletCredited;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.money.Money;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * JSON of the messages stored in wallet_db (outbox_messages.payload and processed_messages.result_payload),
 * following the contract: envelope of spec §4.1, payloads of §4.4, money as a text with two decimals
 * (§1.5) and RFC 3339 UTC timestamps. The relay publishes this JSON as it is.
 */
@Component
public class MessageJsonMapper {

    private static final Map<MessageType, Class<? extends MessagePayload>> RESULT_CLASSES = Map.of(
            MessageType.WALLET_DEBITED, WalletDebited.class,
            MessageType.DEBIT_REJECTED, DebitRejected.class,
            MessageType.WALLET_CREDITED, WalletCredited.class,
            MessageType.CREDIT_FAILED, CreditFailed.class,
            MessageType.DEBIT_REVERSED, DebitReversed.class);

    private final ObjectMapper mapper;

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

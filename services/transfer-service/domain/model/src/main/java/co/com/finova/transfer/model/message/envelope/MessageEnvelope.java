package co.com.finova.transfer.model.message.envelope;

import java.time.Instant;
import java.util.Map;

public class MessageEnvelope {
    private String messageId;
    private String type;
    private String correlationId;
    private String transferId;
    private String producer;
    private String exchange;
    private String routingKey;
    private Instant occurredAt;
    private Object payload;

    public MessageEnvelope() {
    }

    public MessageEnvelope(String messageId, String type, String correlationId,
                           String transferId, String producer, String exchange, String routingKey,
                           Instant occurredAt, Object payload) {
        this.messageId = messageId;
        this.type = type;
        this.correlationId = correlationId;
        this.transferId = transferId;
        this.producer = producer;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.occurredAt = occurredAt;
        this.payload = payload;
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getProducer() { return producer; }
    public void setProducer(String producer) { this.producer = producer; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    @SuppressWarnings("unchecked")
    public <T> T getPayloadAs(Class<T> clazz) {
        if (clazz.isInstance(payload)) {
            return clazz.cast(payload);
        }
        if (payload instanceof Map) {
            return clazz.cast(((Map<?, ?>) payload));
        }
        throw new IllegalArgumentException("Cannot cast payload to " + clazz.getName());
    }
}

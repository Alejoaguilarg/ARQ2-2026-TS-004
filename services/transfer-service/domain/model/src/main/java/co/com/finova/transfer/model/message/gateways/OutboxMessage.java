package co.com.finova.transfer.model.message.gateways;

import java.time.Instant;

public class OutboxMessage {
    private String id;
    private String exchange;
    private String routingKey;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;
    private String type;
    private String correlationId;
    private String transferId;
    private String producer;
    private Instant occurredAt;

    public OutboxMessage() {
    }

    public OutboxMessage(String id, String exchange, String routingKey,
                         String payload, Instant createdAt, Instant publishedAt) {
        this.id = id;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.payload = payload;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
    }

    public OutboxMessage(String id, String exchange, String routingKey,
                         String payload, Instant createdAt, Instant publishedAt,
                         String type, String correlationId, String transferId,
                         String producer, Instant occurredAt) {
        this.id = id;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.payload = payload;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.type = type;
        this.correlationId = correlationId;
        this.transferId = transferId;
        this.producer = producer;
        this.occurredAt = occurredAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getProducer() { return producer; }
    public void setProducer(String producer) { this.producer = producer; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}

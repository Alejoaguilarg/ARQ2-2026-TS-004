package co.com.finova.transfer.model.message.gateways;

import java.time.Instant;

public class OutboxMessage {
    private String id;
    private String exchange;
    private String routingKey;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;

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

    public boolean isPublished() {
        return publishedAt != null;
    }
}

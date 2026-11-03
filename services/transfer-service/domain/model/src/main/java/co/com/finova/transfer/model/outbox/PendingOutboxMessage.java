package co.com.finova.transfer.model.outbox;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.message.OutboundMessage;

import java.util.UUID;

/**
 * An outbox_messages row the relay still has to publish (spec §1.4, ADR 002). json is the stored envelope,
 * published as it is; correlationId and transferId come from that envelope and are kept for the logs
 * (transferId is null when the message belongs to no transfer). attempts counts the failed publishes so far
 * (audit finding H1).
 */
public record PendingOutboxMessage(UUID id, UUID correlationId, UUID transferId, String exchange,
                                   String routingKey, String json, int attempts) {

    public PendingOutboxMessage {
        Contract.requireNonNull(id, "id");
        Contract.requireNonNull(correlationId, "correlationId");
        Contract.requireNotBlank(exchange, "exchange");
        Contract.requireNotBlank(routingKey, "routingKey");
        Contract.requireNotBlank(json, "json");
        Contract.require(attempts >= 0, "attempts must be >= 0: " + attempts);
    }

    /** A row that never failed to publish. */
    public PendingOutboxMessage(UUID id, UUID correlationId, UUID transferId, String exchange, String routingKey,
                                String json) {
        this(id, correlationId, transferId, exchange, routingKey, json, 0);
    }

    /** The message handed to the broker: the AMQP message-id is the envelope messageId (spec §4.1). */
    public OutboundMessage toOutbound() {
        return new OutboundMessage(id.toString(), correlationId.toString(), exchange, routingKey, json);
    }
}

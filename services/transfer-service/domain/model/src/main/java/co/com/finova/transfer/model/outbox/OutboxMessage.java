package co.com.finova.transfer.model.outbox;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.message.Destination;
import co.com.finova.transfer.model.message.MessageEnvelope;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of outbox_messages (spec §2 OutboxMessage, §1.4), written in the same transaction as the state
 * change. id equals the envelope messageId; exchange and routing key follow the topology of §4.2.
 */
public record OutboxMessage(UUID id, String exchange, String routingKey, MessageEnvelope<?> envelope,
                            Instant createdAt) {

    public OutboxMessage {
        Contract.requireNonNull(id, "id");
        Contract.requireNonNull(envelope, "envelope");
        Contract.requireNonNull(createdAt, "createdAt");
        Contract.require(id.equals(envelope.messageId()), "id must equal the envelope messageId");
        Destination expected = envelope.type().publishDestination();
        Contract.require(expected.equals(new Destination(exchange, routingKey)),
                envelope.type().contractName() + " must be published to " + expected.exchange() + " / "
                        + expected.routingKey());
    }

    /** A pending row for a new message. */
    public static OutboxMessage of(MessageEnvelope<?> envelope, Instant createdAt) {
        Contract.requireNonNull(envelope, "envelope");
        Destination destination = envelope.type().publishDestination();
        return new OutboxMessage(envelope.messageId(), destination.exchange(), destination.routingKey(), envelope,
                createdAt);
    }
}

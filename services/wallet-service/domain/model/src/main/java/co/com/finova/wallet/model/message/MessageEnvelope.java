package co.com.finova.wallet.model.message;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.MessagePayload;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope of every message (spec §4.1). messageId is also the AMQP message-id property and the key
 * consumers deduplicate on; transferId is null when the message belongs to no transfer.
 */
public record MessageEnvelope<P extends MessagePayload>(UUID messageId, MessageType type, UUID correlationId,
                                                        UUID transferId, String producer, Instant occurredAt,
                                                        P payload) {

    public static final String WALLET_PRODUCER = "wallet-service";

    public MessageEnvelope {
        Contract.requireNonNull(messageId, "messageId");
        Contract.requireNonNull(type, "type");
        Contract.requireNonNull(correlationId, "correlationId");
        Contract.requireNotBlank(producer, "producer");
        Contract.requireNonNull(occurredAt, "occurredAt");
        Contract.requireNonNull(payload, "payload");
        Contract.require(type == payload.type(), "envelope type " + type.contractName()
                + " does not match payload " + payload.type().contractName());
        if (payload instanceof AttemptRecorded attempt) {
            Contract.require(attempt.eventId().equals(messageId),
                    "AttemptRecorded.eventId must equal the envelope messageId (spec §4.4)");
        }
    }

    /** A new message produced by the Wallet; the type comes from the payload. */
    public static <P extends MessagePayload> MessageEnvelope<P> create(UUID messageId, UUID correlationId,
                                                                       UUID transferId, Instant occurredAt,
                                                                       P payload) {
        Contract.requireNonNull(payload, "payload");
        return new MessageEnvelope<>(messageId, payload.type(), correlationId, transferId, WALLET_PRODUCER,
                occurredAt, payload);
    }
}

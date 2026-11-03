package co.com.finova.transfer.model.processedmessage.gateways;

import java.time.Instant;
import java.util.UUID;

/** processed_messages of transfer_db (spec §2 ProcessedMessage): PRIMARY KEY (message_id, consumer). */
public interface ProcessedMessageRepository {

    /**
     * Records the message in the same transaction as its effect. Returns false when the consumer had
     * already processed it, so the caller applies nothing (spec §4.1: consumers deduplicate on messageId).
     */
    boolean markProcessed(UUID messageId, String consumer, Instant processedAt);
}

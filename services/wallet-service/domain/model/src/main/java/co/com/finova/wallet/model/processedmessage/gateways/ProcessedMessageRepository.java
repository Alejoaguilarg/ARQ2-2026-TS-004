package co.com.finova.wallet.model.processedmessage.gateways;

import co.com.finova.wallet.model.processedmessage.ProcessedMessage;

import java.util.Optional;
import java.util.UUID;

public interface ProcessedMessageRepository {

    Optional<ProcessedMessage> find(UUID messageId, String consumer);

    /** Inserts the row in the same transaction as the effect; PRIMARY KEY (message_id, consumer). */
    void save(ProcessedMessage message);
}

package co.com.finova.wallet.model.processedmessage.gateways;

import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;

import java.util.Optional;
import java.util.UUID;

public interface ProcessedMessageRepository {

    Optional<ProcessedMessage> find(UUID messageId, String consumer);

    /** Inserts the row in the same transaction as the effect; PRIMARY KEY (message_id, consumer). */
    void save(ProcessedMessage message);

    /**
     * A result of this type already stored for the transfer, such as the WalletCredited written together
     * with the CREDITED settlement. It is re-emitted unchanged when the transfer is already settled (§4.7).
     */
    Optional<MessagePayload> findStoredResult(UUID transferId, MessageType resultType);
}

package co.com.finova.wallet.model.processedmessage;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.MessagePayload;

import java.time.Instant;
import java.util.UUID;

/**
 * A message already handled by a consumer (spec §2 ProcessedMessage). The Wallet also stores the
 * result it emitted, so a repeated command is answered with the original result (spec §4.7).
 */
public record ProcessedMessage(UUID messageId, String consumer, Instant processedAt,
                               MessageType resultType, MessagePayload resultPayload) {

    public ProcessedMessage {
        Contract.requireNonNull(messageId, "messageId");
        Contract.requireNotBlank(consumer, "consumer");
        Contract.requireNonNull(processedAt, "processedAt");
        Contract.require((resultType == null) == (resultPayload == null),
                "resultType and resultPayload must be both present or both absent");
        if (resultType != null) {
            Contract.require(resultType.isResult(), resultType.contractName() + " is not a Wallet result");
            Contract.require(resultPayload.type() == resultType, "resultType " + resultType.contractName()
                    + " does not match resultPayload " + resultPayload.type().contractName());
        }
    }

    public static ProcessedMessage withResult(UUID messageId, String consumer, Instant processedAt,
                                              MessagePayload result) {
        Contract.requireNonNull(result, "resultPayload");
        return new ProcessedMessage(messageId, consumer, processedAt, result.type(), result);
    }
}

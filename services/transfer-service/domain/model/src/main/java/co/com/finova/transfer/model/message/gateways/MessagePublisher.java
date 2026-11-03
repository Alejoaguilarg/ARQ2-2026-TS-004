package co.com.finova.transfer.model.message.gateways;

import co.com.finova.transfer.model.message.OutboundMessage;

public interface MessagePublisher {

    // Returns only after a broker confirm; throws MessagePublishException so the outbox row stays pending.
    void publish(OutboundMessage message);
}

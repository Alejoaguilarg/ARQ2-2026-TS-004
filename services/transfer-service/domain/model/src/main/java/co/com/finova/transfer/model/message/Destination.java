package co.com.finova.transfer.model.message;

import co.com.finova.transfer.model.common.Contract;

/** Where a message is published: exchange and routing key (spec §4.2). */
public record Destination(String exchange, String routingKey) {

    public Destination {
        Contract.requireNotBlank(exchange, "exchange");
        Contract.requireNotBlank(routingKey, "routingKey");
    }
}

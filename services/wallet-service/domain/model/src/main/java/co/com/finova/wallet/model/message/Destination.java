package co.com.finova.wallet.model.message;

import co.com.finova.wallet.model.common.Contract;

/** Where a message is published: exchange and routing key (spec §4.2). */
public record Destination(String exchange, String routingKey) {

    public Destination {
        Contract.requireNotBlank(exchange, "exchange");
        Contract.requireNotBlank(routingKey, "routingKey");
    }
}

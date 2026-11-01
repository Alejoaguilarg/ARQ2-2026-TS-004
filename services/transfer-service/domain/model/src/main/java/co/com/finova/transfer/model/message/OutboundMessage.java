package co.com.finova.transfer.model.message;

public record OutboundMessage(
        String messageId,
        String correlationId,
        String exchange,
        String routingKey,
        String payload) {
}

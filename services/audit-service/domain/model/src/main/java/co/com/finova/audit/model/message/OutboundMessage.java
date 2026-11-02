package co.com.finova.wallet.model.message;

public record OutboundMessage(
        String messageId,
        String correlationId,
        String exchange,
        String routingKey,
        String payload) {
}

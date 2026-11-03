package co.com.finova.transfer.rabbitmqpublisher;

import co.com.finova.transfer.model.message.envelope.MessageEnvelope;
import co.com.finova.transfer.model.message.gateways.OutboxMessage;
import co.com.finova.transfer.model.message.gateways.OutboxRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class OutboxRabbitMQPublisher {

    private final OutboxRepository outboxRepository;
    private final RabbitMQMessagePublisher publisher;

    public OutboxRabbitMQPublisher(OutboxRepository outboxRepository,
                                   RabbitMQMessagePublisher publisher) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        List<OutboxMessage> pending = outboxRepository.findPending();
        for (OutboxMessage msg : pending) {
            MessageEnvelope envelope = new MessageEnvelope(
                msg.getId(),
                msg.getType(),
                msg.getCorrelationId(),
                msg.getTransferId(),
                "transfer-service",
                msg.getExchange(),
                msg.getRoutingKey(),
                msg.getOccurredAt(),
                msg.getPayload()
            );
            publisher.publish(envelope);
            msg.setPublishedAt(Instant.now());
            outboxRepository.save(msg);
        }
    }
}

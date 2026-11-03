package co.com.finova.transfer.rabbitmqconsumer;

import co.com.finova.transfer.messagejson.MessageJsonMapper;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.usecase.handlewalletresult.HandleWalletResultUseCase;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wallet results on transfer.wallet-events.q (spec §4.2, §4.6): reads the envelope and hands it to the saga, which
 * owns every business rule. The queue already exists; nothing is declared here.
 */
@Component
public class WalletEventsListener {

    static final String QUEUE = "transfer.wallet-events.q";

    private final MessageJsonMapper json;
    private final DeliveryOutcomeHandler outcomes;
    private final HandleWalletResultUseCase handleResult;

    public WalletEventsListener(MessageJsonMapper json, DeliveryOutcomeHandler outcomes,
                                HandleWalletResultUseCase handleResult) {
        this.json = json;
        this.outcomes = outcomes;
        this.handleResult = handleResult;
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        try (MessageLogContext logContext = MessageLogContext.open(message)) {
            outcomes.process(message, channel, QUEUE, () -> {
                MessageEnvelope<?> envelope = json.readInbound(new String(message.getBody(), StandardCharsets.UTF_8));
                logContext.fill(envelope);
                handleResult.handle(envelope);
            });
        }
    }
}

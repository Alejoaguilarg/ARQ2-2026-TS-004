package co.com.finova.wallet.rabbitmqconsumer;

import co.com.finova.wallet.messagejson.MessageJsonMapper;
import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.usecase.projectbalance.ProjectBalanceUseCase;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * BalanceChanged on wallet.balance-projection.q (spec §4.2, §4.5, ADR 004): projects it into the Redis read
 * model. A Redis outage is a technical error, so the event is retried and, after the limit, dead-lettered.
 */
@Component
public class BalanceProjectionListener {

    static final String QUEUE = "wallet.balance-projection.q";

    private final MessageJsonMapper json;
    private final DeliveryOutcomeHandler outcomes;
    private final ProjectBalanceUseCase projectBalance;

    public BalanceProjectionListener(MessageJsonMapper json, DeliveryOutcomeHandler outcomes,
                                     ProjectBalanceUseCase projectBalance) {
        this.json = json;
        this.outcomes = outcomes;
        this.projectBalance = projectBalance;
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        try (MessageLogContext logContext = MessageLogContext.open(message)) {
            outcomes.process(message, channel, QUEUE, () -> project(message, logContext));
        }
    }

    // readInbound returns BalanceChanged for that type, so the cast is safe.
    @SuppressWarnings("unchecked")
    private void project(Message message, MessageLogContext logContext) {
        MessageEnvelope<?> envelope = json.readInbound(new String(message.getBody(), StandardCharsets.UTF_8));
        logContext.fill(envelope);
        if (envelope.type() != MessageType.BALANCE_CHANGED) {
            throw new ContractViolationException(envelope.type().contractName() + " does not belong to " + QUEUE);
        }
        projectBalance.handle((MessageEnvelope<BalanceChanged>) envelope);
    }
}

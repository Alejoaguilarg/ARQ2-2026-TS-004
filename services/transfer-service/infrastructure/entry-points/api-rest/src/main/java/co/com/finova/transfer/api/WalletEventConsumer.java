package co.com.finova.transfer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.com.finova.transfer.model.message.event.CreditFailed;
import co.com.finova.transfer.model.message.event.DebitRejected;
import co.com.finova.transfer.model.message.event.DebitReversed;
import co.com.finova.transfer.model.message.event.WalletCredited;
import co.com.finova.transfer.model.message.event.WalletDebited;
import co.com.finova.transfer.usecase.TransferSaga;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class WalletEventConsumer {

    private final TransferSaga saga;
    private final ObjectMapper objectMapper;

    public WalletEventConsumer(TransferSaga saga, ObjectMapper objectMapper) {
        this.saga = saga;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "transfer.wallet-events.q", ackMode = "MANUAL")
    public void onMessage(Message message, com.rabbitmq.client.Channel channel) throws Exception {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            switch (routingKey) {
                case "wallet.debited":
                    saga.onWalletDebited(objectMapper.readValue(body, WalletDebited.class));
                    break;
                case "wallet.debit-rejected":
                    saga.onWalletDebitRejected(objectMapper.readValue(body, DebitRejected.class));
                    break;
                case "wallet.credited":
                    saga.onWalletCredited(objectMapper.readValue(body, WalletCredited.class));
                    break;
                case "wallet.credit-failed":
                    saga.onCreditFailed(objectMapper.readValue(body, CreditFailed.class));
                    break;
                case "wallet.debit-reversed":
                    saga.onDebitReversed(objectMapper.readValue(body, DebitReversed.class));
                    break;
                default:
                    channel.basicReject(deliveryTag, false);
                    return;
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            channel.basicReject(deliveryTag, false);
        }
    }
}

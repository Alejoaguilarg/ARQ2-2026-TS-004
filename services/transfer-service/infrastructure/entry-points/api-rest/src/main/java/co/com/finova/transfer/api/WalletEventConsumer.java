package co.com.finova.transfer.api;

import co.com.finova.transfer.model.message.event.CreditFailed;
import co.com.finova.transfer.model.message.event.DebitRejected;
import co.com.finova.transfer.model.message.event.DebitReversed;
import co.com.finova.transfer.model.message.event.WalletCredited;
import co.com.finova.transfer.model.message.event.WalletDebited;
import co.com.finova.transfer.usecase.TransferSaga;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class WalletEventConsumer {

    private final TransferSaga saga;

    public WalletEventConsumer(TransferSaga saga) {
        this.saga = saga;
    }

    @RabbitListener(queues = "transfer.wallet-events.q")
    public void onWalletEvent(Message message, WalletDebited event) {
        saga.onWalletDebited(event);
    }

    @RabbitListener(queues = "transfer.wallet-events.q")
    public void onWalletDebitRejected(Message message, DebitRejected event) {
        saga.onWalletDebitRejected(event);
    }

    @RabbitListener(queues = "transfer.wallet-events.q")
    public void onWalletCredited(Message message, WalletCredited event) {
        saga.onWalletCredited(event);
    }

    @RabbitListener(queues = "transfer.wallet-events.q")
    public void onCreditFailed(Message message, CreditFailed event) {
        saga.onCreditFailed(event);
    }

    @RabbitListener(queues = "transfer.wallet-events.q")
    public void onDebitReversed(Message message, DebitReversed event) {
        saga.onDebitReversed(event);
    }
}

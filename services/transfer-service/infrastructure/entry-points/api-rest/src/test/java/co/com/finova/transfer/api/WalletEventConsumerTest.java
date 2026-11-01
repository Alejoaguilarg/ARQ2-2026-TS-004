package co.com.finova.transfer.api;

import co.com.finova.transfer.model.message.event.CreditFailed;
import co.com.finova.transfer.model.message.event.DebitRejected;
import co.com.finova.transfer.model.message.event.DebitReversed;
import co.com.finova.transfer.model.message.event.WalletCredited;
import co.com.finova.transfer.model.message.event.WalletDebited;
import co.com.finova.transfer.usecase.TransferSaga;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class WalletEventConsumerTest {

    private TransferSaga saga;
    private WalletEventConsumer consumer;

    @BeforeEach
    void setUp() {
        saga = mock(TransferSaga.class);
        consumer = new WalletEventConsumer(saga);
    }

    @Test
    void onWalletDebited_callsSaga() {
        Message message = new Message(new byte[0], new MessageProperties());
        WalletDebited event = new WalletDebited("t-1", "w-1", "user-1", "100.00", 1);

        consumer.onWalletDebited(message, event);

        verify(saga).onWalletDebited(event);
    }

    @Test
    void onWalletDebitRejected_callsSaga() {
        Message message = new Message(new byte[0], new MessageProperties());
        DebitRejected event = new DebitRejected("t-1", "user-1", DebitRejected.Reason.INSUFFICIENT_FUNDS);

        consumer.onWalletDebitRejected(message, event);

        verify(saga).onWalletDebitRejected(event);
    }

    @Test
    void onWalletCredited_callsSaga() {
        Message message = new Message(new byte[0], new MessageProperties());
        WalletCredited event = new WalletCredited("t-1", "w-1", "user-1", "100.00", 1);

        consumer.onWalletCredited(message, event);

        verify(saga).onWalletCredited(event);
    }

    @Test
    void onCreditFailed_callsSaga() {
        Message message = new Message(new byte[0], new MessageProperties());
        CreditFailed event = new CreditFailed("t-1", "user-1", 1, CreditFailed.Reason.RECIPIENT_WALLET_NOT_FOUND);

        consumer.onCreditFailed(message, event);

        verify(saga).onCreditFailed(event);
    }

    @Test
    void onDebitReversed_callsSaga() {
        Message message = new Message(new byte[0], new MessageProperties());
        DebitReversed event = new DebitReversed("t-1", "w-1", "user-1", "100.00", 1);

        consumer.onDebitReversed(message, event);

        verify(saga).onDebitReversed(event);
    }

    @Test
    void allHandlers_doNotThrow() {
        Message message = new Message(new byte[0], new MessageProperties());

        assertDoesNotThrow(() -> consumer.onWalletDebited(message, new WalletDebited("t-1", "w-1", "u-1", "100.00", 1)));
        assertDoesNotThrow(() -> consumer.onWalletDebitRejected(message, new DebitRejected("t-1", "u-1", DebitRejected.Reason.INSUFFICIENT_FUNDS)));
        assertDoesNotThrow(() -> consumer.onWalletCredited(message, new WalletCredited("t-1", "w-1", "u-1", "100.00", 1)));
        assertDoesNotThrow(() -> consumer.onCreditFailed(message, new CreditFailed("t-1", "u-1", 1, CreditFailed.Reason.RECIPIENT_WALLET_NOT_FOUND)));
        assertDoesNotThrow(() -> consumer.onDebitReversed(message, new DebitReversed("t-1", "w-1", "u-1", "100.00", 1)));
    }
}

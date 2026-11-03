package co.com.finova.wallet.rabbitmqconsumer;

import co.com.finova.wallet.messagejson.MessageJsonMapper;
import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.usecase.projectbalance.ProjectBalanceUseCase;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** wallet.balance-projection.q without a broker: BalanceChanged reaches the projection; anything else is invalid. */
class BalanceProjectionListenerTest {

    private static final long TAG = 11L;
    private static final String MESSAGE_ID = "2c1f0a3e-6b7d-4e8f-9a0b-1c2d3e4f5a6b";
    private static final String CORRELATION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String USER_ID = "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b";

    private final Channel channel = mock(Channel.class);
    private final ProjectBalanceUseCase projectBalance = mock(ProjectBalanceUseCase.class);
    private final List<String> deadLetters = new ArrayList<>();
    private final BalanceProjectionListener listener = new BalanceProjectionListener(new MessageJsonMapper(),
            new DeliveryOutcomeHandler(new RetryPolicy(3), (message, queue) -> deadLetters.add(queue)),
            projectBalance);

    @Test
    @SuppressWarnings("unchecked")
    void balanceChangedIsProjectedAndAckedWithoutATransferInTheLogContext() throws Exception {
        Map<String, String> logContext = new HashMap<>();
        doAnswer(invocation -> {
            logContext.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(projectBalance).handle(any());

        listener.onMessage(message(balanceChanged("null")), channel);

        ArgumentCaptor<MessageEnvelope<?>> event = ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(projectBalance).handle((MessageEnvelope) event.capture());
        assertThat(event.getValue().type()).isEqualTo(MessageType.BALANCE_CHANGED);
        assertThat(logContext).containsEntry("correlationId", CORRELATION_ID)
                .containsEntry("messageId", MESSAGE_ID)
                .doesNotContainKey("transferId");
        assertThat(MDC.get("correlationId")).isNull();
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void aRedisOutageIsRetriedThroughTheRetryQueue() throws Exception {
        doThrow(new BalanceReadModelUnavailableException("redis down", null)).when(projectBalance).handle(any());

        listener.onMessage(message(balanceChanged("\"9b2f6c1e-3d4a-4f5b-8c7d-1e2f3a4b5c6d\"")), channel);

        verify(channel).basicReject(TAG, false);
        assertThat(deadLetters).isEmpty();
    }

    @Test
    void aCommandDoesNotBelongToTheProjectionQueue() throws Exception {
        String transferId = UUID.randomUUID().toString();
        listener.onMessage(message("""
                {"messageId":"%s","type":"DebitWallet","correlationId":"%s","transferId":"%s",
                 "producer":"transfer-service","occurredAt":"2026-11-02T15:00:00Z",
                 "payload":{"transferId":"%s","userId":"%s","amount":"10.00","currency":"COP"}}"""
                .formatted(MESSAGE_ID, CORRELATION_ID, transferId, transferId, USER_ID)), channel);

        assertThat(deadLetters).containsExactly("wallet.balance-projection.q.dlq");
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(projectBalance);
    }

    private static String balanceChanged(String transferIdJson) {
        return """
                {"messageId":"%s","type":"BalanceChanged","correlationId":"%s","transferId":%s,
                 "producer":"wallet-service","occurredAt":"2026-11-02T15:00:00Z",
                 "payload":{"walletId":"%s","userId":"%s","available":"350000.00","version":4}}"""
                .formatted(MESSAGE_ID, CORRELATION_ID, transferIdJson, UUID.randomUUID(), USER_ID);
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        properties.setMessageId(MESSAGE_ID);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

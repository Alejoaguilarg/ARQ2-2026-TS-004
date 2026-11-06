package co.com.finova.transfer.rabbitmqconsumer;

import co.com.finova.transfer.messagejson.MessageJsonMapper;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.MessageType;
import co.com.finova.transfer.usecase.handlewalletresult.HandleWalletResultUseCase;
import com.rabbitmq.client.Channel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * transfer.wallet-events.q without a broker: the listener reads the envelope, hands it to the saga, acks it, and
 * keeps correlationId, transferId and messageId in the MDC only while the delivery is being handled. It also tags
 * the span Spring AMQP opens for the delivery with transfer.id and message.type (observability only: the spec does
 * not define these tags). Both are high cardinality: they live in the span, never in the spring.rabbit.listener
 * metric.
 */
class WalletEventsListenerTest {

    /** The name Spring AMQP gives the listener observation. */
    private static final String LISTENER_OBSERVATION = "spring.rabbit.listener";

    private static final long TAG = 7L;
    private static final String MESSAGE_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String CORRELATION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String TRANSFER_ID = "9b2f6c1e-3d4a-4f5b-8c7d-1e2f3a4b5c6d";
    private static final String USER_ID = "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b";
    private static final String WALLET_ID = "11111111-2222-4333-8444-555555555555";

    private final Channel channel = mock(Channel.class);
    private final HandleWalletResultUseCase saga = mock(HandleWalletResultUseCase.class);
    private final List<String> deadLetters = new ArrayList<>();
    private final TestObservationRegistry observations = TestObservationRegistry.create();
    private final WalletEventsListener listener = listener(observations);

    private WalletEventsListener listener(ObservationRegistry registry) {
        return new WalletEventsListener(new MessageJsonMapper(),
                new DeliveryOutcomeHandler(new RetryPolicy(3), (message, queue) -> deadLetters.add(queue)), saga,
                registry);
    }

    /** Runs the delivery inside an observation, as Spring AMQP does when observation-enabled is true. */
    private void inListenerObservation(Message message) throws java.io.IOException {
        Observation.createNotStarted(LISTENER_OBSERVATION, observations)
                .observeChecked(() -> listener.onMessage(message, channel));
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        properties.setMessageId(MESSAGE_ID);
        properties.setCorrelationId(CORRELATION_ID);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static String walletDebited() {
        return """
                {"messageId":"%s","type":"WalletDebited","correlationId":"%s","transferId":"%s",
                 "producer":"wallet-service","occurredAt":"2026-11-03T10:00:00Z",
                 "payload":{"transferId":"%s","walletId":"%s","userId":"%s","amount":"10.00","version":2}}"""
                .formatted(MESSAGE_ID, CORRELATION_ID, TRANSFER_ID, TRANSFER_ID, WALLET_ID, USER_ID);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aResultReachesTheSagaWithTheLogContextAndIsAcked() throws Exception {
        Map<String, String> logContext = new HashMap<>();
        doAnswer(invocation -> {
            logContext.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(saga).handle(any());

        listener.onMessage(message(walletDebited()), channel);

        ArgumentCaptor<MessageEnvelope> result = ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(saga).handle(result.capture());
        assertThat(result.getValue().type()).isEqualTo(MessageType.WALLET_DEBITED);
        assertThat(logContext).containsEntry("correlationId", CORRELATION_ID)
                .containsEntry("transferId", TRANSFER_ID)
                .containsEntry("messageId", MESSAGE_ID);
        assertThat(MDC.get("correlationId")).as("MDC cleared after the delivery").isNull();
        assertThat(MDC.get("transferId")).isNull();
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void anUnreadableMessageGoesToTheDlqWithoutReachingTheSaga() throws Exception {
        listener.onMessage(message("{\"type\":\"DebitWallet\",\"payload\":{}}"), channel);

        assertThat(deadLetters).containsExactly("transfer.wallet-events.q.dlq");
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(saga);
    }

    @Test
    void aTechnicalFailureIsRetriedThroughTheRetryQueue() throws Exception {
        doThrow(new IllegalStateException("transfer_db down")).when(saga).handle(any());

        listener.onMessage(message(walletDebited()), channel);

        verify(channel).basicReject(TAG, false);
        assertThat(deadLetters).isEmpty();
    }

    // ---------------------------------------------------------------- span tags

    @Test
    void theListenerSpanCarriesTheTransferAndTheEventType() throws Exception {
        inListenerObservation(message(walletDebited()));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .hasHighCardinalityKeyValue("transfer.id", TRANSFER_ID)
                .hasHighCardinalityKeyValue("message.type", "WalletDebited")
                .doesNotHaveLowCardinalityKeyValueWithKey("transfer.id")
                .satisfies(context -> assertThat(context.getLowCardinalityKeyValues())
                        .as("no tag reaches the spring.rabbit.listener metric").isEmpty());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void aFailedResultKeepsTheTransferTagSoItCanBeFoundInJaeger() throws Exception {
        doThrow(new IllegalStateException("transfer_db down")).when(saga).handle(any());

        inListenerObservation(message(walletDebited()));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .hasHighCardinalityKeyValue("transfer.id", TRANSFER_ID);
        verify(channel).basicReject(TAG, false);
    }

    @Test
    void anUnreadableMessageAddsNoTagsAndStillGoesToTheDlq() throws Exception {
        inListenerObservation(message("{\"type\":\"DebitWallet\",\"payload\":{}}"));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .doesNotHaveHighCardinalityKeyValueWithKey("transfer.id")
                .doesNotHaveHighCardinalityKeyValueWithKey("message.type");
        assertThat(deadLetters).containsExactly("transfer.wallet-events.q.dlq");
    }

    @Test
    void aBrokenObservationRegistryNeverStopsTheResult() throws Exception {
        ObservationRegistry broken = mock(ObservationRegistry.class);
        when(broken.getCurrentObservation()).thenThrow(new IllegalStateException("broken registry"));

        listener(broken).onMessage(message(walletDebited()), channel);

        verify(saga).handle(any());
        verify(channel).basicAck(TAG, false);
    }
}

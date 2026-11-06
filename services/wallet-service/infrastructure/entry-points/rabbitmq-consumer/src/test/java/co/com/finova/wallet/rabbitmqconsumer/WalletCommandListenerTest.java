package co.com.finova.wallet.rabbitmqconsumer;

import co.com.finova.wallet.messagejson.MessageJsonMapper;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.usecase.command.CommandOutcome;
import co.com.finova.wallet.usecase.command.CommandOutcome.Settlement;
import co.com.finova.wallet.usecase.creditwallet.CreditWalletUseCase;
import co.com.finova.wallet.usecase.debitwallet.DebitWalletUseCase;
import co.com.finova.wallet.usecase.reversedebit.ReverseDebitUseCase;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * wallet.commands.q without a broker: the listener reads the envelope, hands it to its use case, acks it, and
 * keeps correlationId, transferId and messageId in the MDC only while the delivery is being handled. It also tags
 * the span Spring AMQP opens for the delivery with the transfer and what the Wallet did (observability only: the
 * spec does not define these tags). Every tag is high cardinality, so it lives in the span and never in the
 * spring.rabbit.listener metric.
 */
class WalletCommandListenerTest {

    private static final long TAG = 7L;
    private static final String MESSAGE_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String CORRELATION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String TRANSFER_ID = "9b2f6c1e-3d4a-4f5b-8c7d-1e2f3a4b5c6d";
    private static final String USER_ID = "f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b";
    /** The name Spring AMQP gives the listener observation. */
    private static final String LISTENER_OBSERVATION = "spring.rabbit.listener";

    private final Channel channel = mock(Channel.class);
    private final DebitWalletUseCase debitWallet = mock(DebitWalletUseCase.class);
    private final CreditWalletUseCase creditWallet = mock(CreditWalletUseCase.class);
    private final ReverseDebitUseCase reverseDebit = mock(ReverseDebitUseCase.class);
    private final List<String> deadLetters = new ArrayList<>();
    private final TestObservationRegistry observations = TestObservationRegistry.create();
    private final WalletCommandListener listener = listener(observations);

    @Test
    @SuppressWarnings("unchecked")
    void debitReachesItsUseCaseWithTheLogContextAndIsAcked() throws Exception {
        Map<String, String> logContext = new HashMap<>();
        doAnswer(invocation -> {
            logContext.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(debitWallet).handle(any());

        listener.onMessage(message(debit()), channel);

        ArgumentCaptor<MessageEnvelope<?>> command = ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(debitWallet).handle((MessageEnvelope) command.capture());
        assertThat(command.getValue().type()).isEqualTo(MessageType.DEBIT_WALLET);
        assertThat(logContext).containsEntry("correlationId", CORRELATION_ID)
                .containsEntry("transferId", TRANSFER_ID)
                .containsEntry("messageId", MESSAGE_ID);
        assertThat(MDC.get("correlationId")).as("MDC cleared after the delivery").isNull();
        assertThat(MDC.get("transferId")).isNull();
        assertThat(MDC.get("messageId")).isNull();
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(creditWallet, reverseDebit);
    }

    @Test
    void creditReachesItsUseCase() throws Exception {
        listener.onMessage(message(credit()), channel);

        verify(creditWallet).handle(any());
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(debitWallet, reverseDebit);
    }

    @Test
    void reverseReachesItsUseCase() throws Exception {
        listener.onMessage(message(reverse()), channel);

        verify(reverseDebit).handle(any());
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(debitWallet, creditWallet);
    }

    @Test
    void balanceChangedDoesNotBelongToTheCommandQueue() throws Exception {
        listener.onMessage(message(envelope("BalanceChanged", """
                {"walletId":"%s","userId":"%s","available":"10.00","version":1}"""
                .formatted(UUID.randomUUID(), USER_ID))), channel);

        assertThat(deadLetters).containsExactly("wallet.commands.q.dlq");
        verify(channel).basicAck(TAG, false);
        verifyNoInteractions(debitWallet, creditWallet, reverseDebit);
    }

    @Test
    void invalidJsonGoesToTheDlqAndLeavesTheMdcClean() throws Exception {
        listener.onMessage(message("{\"messageId\":"), channel);

        assertThat(deadLetters).containsExactly("wallet.commands.q.dlq");
        verify(channel).basicAck(TAG, false);
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("messageId")).isNull();
    }

    // ---------------------------------------------------------------- span tags

    @Test
    void theListenerSpanCarriesTheTransferTheCommandAndWhatTheWalletDid() throws Exception {
        when(debitWallet.handle(any()))
                .thenReturn(new CommandOutcome(MessageType.WALLET_DEBITED, Settlement.NOT_APPLICABLE, false));

        inListenerObservation(message(debit()));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .hasHighCardinalityKeyValue("transfer.id", TRANSFER_ID)
                .hasHighCardinalityKeyValue("message.type", "DebitWallet")
                .hasHighCardinalityKeyValue("wallet.operation", "DEBIT")
                .hasHighCardinalityKeyValue("wallet.result", "WalletDebited")
                .hasHighCardinalityKeyValue("wallet.settlement", "NOT_APPLICABLE")
                .hasHighCardinalityKeyValue("wallet.replayed", "false")
                .doesNotHaveLowCardinalityKeyValueWithKey("transfer.id")
                .satisfies(context -> assertThat(context.getLowCardinalityKeyValues())
                        .as("no tag reaches the spring.rabbit.listener metric").isEmpty());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void aCreditThatFindsTheTransferReversedSaysSoInTheSpan() throws Exception {
        when(creditWallet.handle(any()))
                .thenReturn(new CommandOutcome(MessageType.CREDIT_FAILED, Settlement.ALREADY_REVERSED, false));

        inListenerObservation(message(credit()));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .hasHighCardinalityKeyValue("wallet.operation", "CREDIT")
                .hasHighCardinalityKeyValue("wallet.result", "CreditFailed")
                .hasHighCardinalityKeyValue("wallet.settlement", "ALREADY_REVERSED");
    }

    @Test
    void aRepeatedReversalIsTaggedAsReplayed() throws Exception {
        when(reverseDebit.handle(any()))
                .thenReturn(new CommandOutcome(MessageType.DEBIT_REVERSED, Settlement.NOT_APPLICABLE, true));

        inListenerObservation(message(reverse()));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .hasHighCardinalityKeyValue("message.type", "ReverseDebit")
                .hasHighCardinalityKeyValue("wallet.operation", "REVERSAL")
                .hasHighCardinalityKeyValue("wallet.result", "DebitReversed")
                .hasHighCardinalityKeyValue("wallet.replayed", "true");
    }

    @Test
    void aFailedCommandKeepsTheTransferTagSoItCanBeFoundInJaeger() throws Exception {
        when(debitWallet.handle(any())).thenThrow(new IllegalStateException("wallet_db down"));

        inListenerObservation(message(debit()));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .hasHighCardinalityKeyValue("transfer.id", TRANSFER_ID)
                .hasHighCardinalityKeyValue("wallet.operation", "DEBIT")
                .doesNotHaveHighCardinalityKeyValueWithKey("wallet.result");
        verify(channel).basicReject(TAG, false);
    }

    @Test
    void anUnreadableMessageAddsNoTransferTagAndStillGoesToTheDlq() throws Exception {
        inListenerObservation(message("{\"messageId\":"));

        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(LISTENER_OBSERVATION).that()
                .doesNotHaveHighCardinalityKeyValueWithKey("transfer.id")
                .doesNotHaveHighCardinalityKeyValueWithKey("message.type");
        assertThat(deadLetters).containsExactly("wallet.commands.q.dlq");
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void aBrokenObservationRegistryNeverStopsTheCommand() throws Exception {
        ObservationRegistry broken = mock(ObservationRegistry.class);
        when(broken.getCurrentObservation()).thenThrow(new IllegalStateException("broken registry"));

        listener(broken).onMessage(message(debit()), channel);

        verify(debitWallet).handle(any());
        verify(channel).basicAck(TAG, false);
    }

    // ---------------------------------------------------------------- helpers

    private WalletCommandListener listener(ObservationRegistry registry) {
        return new WalletCommandListener(new MessageJsonMapper(),
                new DeliveryOutcomeHandler(new RetryPolicy(3), (message, queue) -> deadLetters.add(queue)),
                debitWallet, creditWallet, reverseDebit, registry);
    }

    /** Runs the delivery inside an observation, as Spring AMQP does when observation-enabled is true. */
    private void inListenerObservation(Message message) throws IOException {
        Observation.createNotStarted(LISTENER_OBSERVATION, observations)
                .observeChecked(() -> listener.onMessage(message, channel));
    }

    private static String debit() {
        return envelope("DebitWallet", """
                {"transferId":"%s","userId":"%s","amount":"150000.00","currency":"COP"}"""
                .formatted(TRANSFER_ID, USER_ID));
    }

    private static String credit() {
        return envelope("CreditWallet", """
                {"transferId":"%s","userId":"%s","amount":"10.00","currency":"COP","attempt":1}"""
                .formatted(TRANSFER_ID, USER_ID));
    }

    private static String reverse() {
        return envelope("ReverseDebit", """
                {"transferId":"%s","userId":"%s","amount":"10.00","currency":"COP","reason":"CREDIT_TIMEOUT"}"""
                .formatted(TRANSFER_ID, USER_ID));
    }

    private static String envelope(String type, String payload) {
        return """
                {"messageId":"%s","type":"%s","correlationId":"%s","transferId":"%s",
                 "producer":"transfer-service","occurredAt":"2026-11-02T15:00:00Z","payload":%s}"""
                .formatted(MESSAGE_ID, type, CORRELATION_ID, TRANSFER_ID, payload);
    }

    static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        properties.setMessageId(MESSAGE_ID);
        properties.setCorrelationId(CORRELATION_ID);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

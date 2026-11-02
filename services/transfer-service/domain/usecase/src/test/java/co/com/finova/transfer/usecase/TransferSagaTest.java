package co.com.finova.transfer.usecase;

import co.com.finova.transfer.model.message.command.DebitWallet;
import co.com.finova.transfer.model.message.command.ReverseDebit;
import co.com.finova.transfer.model.message.event.AttemptRecorded;
import co.com.finova.transfer.model.message.event.CreditFailed;
import co.com.finova.transfer.model.message.event.DebitRejected;
import co.com.finova.transfer.model.message.event.DebitReversed;
import co.com.finova.transfer.model.message.event.WalletCredited;
import co.com.finova.transfer.model.message.event.WalletDebited;
import co.com.finova.transfer.model.message.gateways.MessagePublisher;
import co.com.finova.transfer.model.message.gateways.OutboxMessage;
import co.com.finova.transfer.model.message.gateways.OutboxRepository;
import co.com.finova.transfer.model.message.gateways.ProcessedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferSagaTest {

    private OutboxRepository outboxRepository;
    private ProcessedMessageRepository processedMessageRepository;
    private MessagePublisher messagePublisher;
    private TransferSaga saga;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        processedMessageRepository = mock(ProcessedMessageRepository.class);
        messagePublisher = mock(MessagePublisher.class);
        saga = new TransferSaga(outboxRepository, processedMessageRepository, messagePublisher);
    }

    @Test
    void createTransfer_whenNotProcessed_createsTransfer() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(false);

        saga.createTransfer("t-1", "user-1", "user-2", "100.00", "COP", "key-1", "hash-1", "corr-1");

        verify(processedMessageRepository).save("t-1", "transfer-service");
        verify(outboxRepository, times(2)).save(any(OutboxMessage.class));
    }

    @Test
    void createTransfer_whenAlreadyProcessed_doesNothing() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(true);

        saga.createTransfer("t-1", "user-1", "user-2", "100.00", "COP", "key-1", "hash-1", "corr-1");

        verify(processedMessageRepository, never()).save(anyString(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void onWalletDebited_whenNotProcessed_sendsCredit() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(false);

        WalletDebited event = new WalletDebited("t-1", "w-1", "user-1", "100.00", "COP", 1);
        saga.onWalletDebited(event);

        verify(processedMessageRepository).save("t-1", "transfer-service");
        verify(outboxRepository).save(any(OutboxMessage.class));
    }

    @Test
    void onWalletDebited_whenAlreadyProcessed_doesNothing() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(true);

        WalletDebited event = new WalletDebited("t-1", "w-1", "user-1", "100.00", "COP", 1);
        saga.onWalletDebited(event);

        verify(processedMessageRepository, never()).save(anyString(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void onWalletDebitRejected_whenNotProcessed_publishesRejected() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(false);

        DebitRejected event = new DebitRejected("t-1", "user-1", "100.00", DebitRejected.Reason.INSUFFICIENT_FUNDS);
        saga.onWalletDebitRejected(event);

        verify(processedMessageRepository).save("t-1", "transfer-service");
        verify(outboxRepository, times(2)).save(any(OutboxMessage.class));
    }

    @Test
    void onCreditFailed_belowMaxAttempts_resendsCredit() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(false);

        CreditFailed event = new CreditFailed("t-1", "user-1", 1, CreditFailed.Reason.RECIPIENT_WALLET_NOT_FOUND, "100.00", "COP");
        saga.onCreditFailed(event);

        verify(processedMessageRepository).save("t-1", "transfer-service");
        verify(outboxRepository).save(any(OutboxMessage.class));
    }

    @Test
    void onCreditFailed_atMaxAttempts_compensates() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(false);

        CreditFailed event = new CreditFailed("t-1", "user-1", 3, CreditFailed.Reason.RECIPIENT_WALLET_NOT_FOUND, "100.00", "COP");
        saga.onCreditFailed(event);

        verify(processedMessageRepository).save("t-1", "transfer-service");
        verify(outboxRepository, atLeastOnce()).save(any(OutboxMessage.class));
    }

    @Test
    void onDebitReversed_whenNotProcessed_publishesReversed() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(false);

        DebitReversed event = new DebitReversed("t-1", "w-1", "user-1", "100.00", 1);
        saga.onDebitReversed(event);

        verify(processedMessageRepository).save("t-1", "transfer-service");
        verify(outboxRepository, times(2)).save(any(OutboxMessage.class));
    }

    @Test
    void onWalletCredited_whenNotProcessed_publishesCompleted() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(false);

        WalletCredited event = new WalletCredited("t-1", "w-1", "user-1", "100.00", 1);
        saga.onWalletCredited(event);

        verify(processedMessageRepository).save("t-1", "transfer-service");
        verify(outboxRepository, times(2)).save(any(OutboxMessage.class));
    }

    @Test
    void onCreditFailed_whenAlreadyProcessed_doesNothing() {
        when(processedMessageRepository.exists("t-1", "transfer-service")).thenReturn(true);

        CreditFailed event = new CreditFailed("t-1", "user-1", 1, CreditFailed.Reason.RECIPIENT_WALLET_NOT_FOUND, "100.00", "COP");
        saga.onCreditFailed(event);

        verify(processedMessageRepository, never()).save(anyString(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void compensate_createsReverseDebit() {
        saga.compensate("t-1", "user-1", "100.00", "COP",
                ReverseDebit.Reason.CREDIT_TIMEOUT, java.time.Instant.now());

        verify(outboxRepository, atLeastOnce()).save(any(OutboxMessage.class));
    }

    @Test
    void timeoutSweeper_doesNotThrow() {
        assertDoesNotThrow(() -> saga.timeoutSweeper());
    }
}

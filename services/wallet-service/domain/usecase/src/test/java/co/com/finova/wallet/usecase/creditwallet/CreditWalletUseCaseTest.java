package co.com.finova.wallet.usecase.creditwallet;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.ledger.DebitMismatchException;
import co.com.finova.wallet.model.ledger.LedgerEntryType;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptSource;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.CreditFailed;
import co.com.finova.wallet.model.message.payload.CreditFailureReason;
import co.com.finova.wallet.model.message.payload.CreditWallet;
import co.com.finova.wallet.model.message.payload.WalletCredited;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import co.com.finova.wallet.model.settlement.SettlementOutcome;
import co.com.finova.wallet.model.settlement.TransferSettlement;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.usecase.command.WalletCommandSupport;
import co.com.finova.wallet.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static co.com.finova.wallet.usecase.support.InMemoryPorts.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Orchestration of the credit (spec §4.7, §4.8) with in-memory ports. */
class CreditWalletUseCaseTest {

    private final InMemoryPorts.Wallets wallets = new InMemoryPorts.Wallets();
    private final InMemoryPorts.Ledger ledger = new InMemoryPorts.Ledger();
    private final InMemoryPorts.Settlements settlements = new InMemoryPorts.Settlements();
    private final InMemoryPorts.ProcessedMessages processed = new InMemoryPorts.ProcessedMessages();
    private final InMemoryPorts.Outbox outbox = new InMemoryPorts.Outbox();
    private final InMemoryPorts.Transactions transactions = new InMemoryPorts.Transactions();
    private final InMemoryPorts.CapturedLogs logs = new InMemoryPorts.CapturedLogs();
    private final Logger useCaseLogger = Logger.getLogger(CreditWalletUseCase.class.getName());

    private final CreditWalletUseCase useCase = new CreditWalletUseCase(wallets, ledger, settlements, processed,
            outbox, transactions, new InMemoryPorts.RandomIds(), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void debitedTransfer() {
        useCaseLogger.addHandler(logs);
        Wallet sender = wallets.add(senderId, "350000.00", 1);
        ledger.debited(transferId, sender, "150000.00");
    }

    @AfterEach
    void releaseLogs() {
        useCaseLogger.removeHandler(logs);
    }

    @Test
    void appliedCreditClaimsSettlementAndWritesLedgerResultBalanceChangedAndAudit() {
        Wallet recipient = wallets.add(recipientId, "100000.00", 5);
        MessageEnvelope<CreditWallet> command = command(1, "150000.00");

        useCase.handle(command);

        assertThat(transactions.count).isEqualTo(1);
        assertThat(settlements.rows.get(transferId))
                .isEqualTo(new TransferSettlement(transferId, SettlementOutcome.CREDITED, NOW));
        assertThat(wallets.of(recipientId).balance()).isEqualTo(Money.parse("250000.00"));
        assertThat(ledger.ofType(LedgerEntryType.CREDIT)).singleElement().satisfies(entry -> {
            assertThat(entry.walletId()).isEqualTo(recipient.walletId());
            assertThat(entry.transferId()).isEqualTo(transferId);
            assertThat(entry.amount()).isEqualTo(Money.parse("150000.00"));
            assertThat(entry.balanceAfter()).isEqualTo(Money.parse("250000.00"));
            assertThat(entry.createdAt()).isEqualTo(NOW);
        });

        WalletCredited expected = new WalletCredited(transferId, recipient.walletId(), recipientId,
                Money.parse("150000.00"), 6);
        assertThat(storedResult(command)).isEqualTo(expected);
        assertThat(outbox.types()).containsExactly(
                MessageType.WALLET_CREDITED, MessageType.BALANCE_CHANGED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(expected);
        assertThat(outbox.payload(1)).isEqualTo(new BalanceChanged(recipient.walletId(), recipientId,
                Money.parse("250000.00"), 6));
        assertAudit(outbox.message(2), AttemptOutcome.APPLIED, null);
        assertEnvelopes(command);
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("applied", correlationId.toString(), transferId.toString()));
    }

    @Test
    void walletUpdatedAtAndBalanceChangedOccurredAtComeFromOneClockReadInMicros() {
        InMemoryPorts.SteppingClock clock = new InMemoryPorts.SteppingClock();
        CreditWalletUseCase steppingUseCase = new CreditWalletUseCase(wallets, ledger, settlements, processed,
                outbox, transactions, new InMemoryPorts.RandomIds(), clock);
        wallets.add(recipientId, "100000.00", 5);

        steppingUseCase.handle(command(1, "150000.00"));

        assertThat(clock.reads).isEqualTo(1);
        assertThat(wallets.of(recipientId).updatedAt()).isEqualTo(InMemoryPorts.SteppingClock.START_MICROS);
        assertThat(outbox.message(1).envelope().occurredAt()).isEqualTo(InMemoryPorts.SteppingClock.START_MICROS);
    }

    @Test
    void missingRecipientWalletFailsWithoutClaimingTheSettlement() {
        MessageEnvelope<CreditWallet> command = command(2, "150000.00");

        useCase.handle(command);

        CreditFailed expected = new CreditFailed(transferId, recipientId, 2,
                CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND);
        assertThat(settlements.rows).isEmpty();
        assertThat(ledger.ofType(LedgerEntryType.CREDIT)).isEmpty();
        assertThat(storedResult(command)).isEqualTo(expected);
        assertThat(outbox.types()).containsExactly(MessageType.CREDIT_FAILED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(expected);
        assertAudit(outbox.message(1), AttemptOutcome.FAILED, "RECIPIENT_WALLET_NOT_FOUND");
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("RECIPIENT_WALLET_NOT_FOUND", correlationId.toString()));
    }

    @Test
    void laterAttemptReemitsOriginalWalletCreditedWithoutAuditOrEffect() {
        wallets.add(recipientId, "100000.00", 5);
        useCase.handle(command(1, "150000.00"));
        WalletCredited original = (WalletCredited) outbox.payload(0);
        outbox.messages.clear();
        int changes = wallets.changes;
        MessageEnvelope<CreditWallet> secondAttempt = command(2, "150000.00");

        useCase.handle(secondAttempt);

        assertThat(wallets.changes).isEqualTo(changes);
        assertThat(ledger.ofType(LedgerEntryType.CREDIT)).hasSize(1);
        assertThat(outbox.types()).containsExactly(MessageType.WALLET_CREDITED);
        assertThat(outbox.payload(0)).isEqualTo(original);
        assertThat(storedResult(secondAttempt)).isEqualTo(original);
        assertEnvelopes(secondAttempt);
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("already credited", transferId.toString()));
    }

    @Test
    void lateCreditAfterReversalFailsWithTransferReversed() {
        wallets.add(recipientId, "100000.00", 5);
        settlements.rows.put(transferId, TransferSettlement.reversed(transferId, NOW.minusSeconds(60)));
        MessageEnvelope<CreditWallet> command = command(3, "150000.00");

        useCase.handle(command);

        CreditFailed expected = new CreditFailed(transferId, recipientId, 3, CreditFailureReason.TRANSFER_REVERSED);
        assertThat(wallets.of(recipientId).balance()).isEqualTo(Money.parse("100000.00"));
        assertThat(ledger.ofType(LedgerEntryType.CREDIT)).isEmpty();
        assertThat(settlements.rows.get(transferId).outcome()).isEqualTo(SettlementOutcome.REVERSED);
        assertThat(storedResult(command)).isEqualTo(expected);
        assertThat(outbox.types()).containsExactly(MessageType.CREDIT_FAILED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(expected);
        assertAudit(outbox.message(1), AttemptOutcome.FAILED, "TRANSFER_REVERSED");
    }

    @Test
    void repeatedCommandRepublishesStoredResultOnly() {
        wallets.add(recipientId, "100000.00", 5);
        MessageEnvelope<CreditWallet> command = command(1, "150000.00");
        useCase.handle(command);
        Object original = outbox.payload(0);
        outbox.messages.clear();

        useCase.handle(command);

        assertThat(processed.saves).isEqualTo(1);
        assertThat(ledger.ofType(LedgerEntryType.CREDIT)).hasSize(1);
        assertThat(outbox.types()).containsExactly(MessageType.WALLET_CREDITED);
        assertThat(outbox.payload(0)).isEqualTo(original);
        assertThat(outbox.message(0).id()).isNotEqualTo(command.messageId());
    }

    @Test
    void creditWithoutDebitIsRejectedAsMismatchAndLogged() {
        wallets.add(recipientId, "100000.00", 5);
        UUID unknownTransfer = UUID.randomUUID();
        MessageEnvelope<CreditWallet> command = command(unknownTransfer, 1, "150000.00");

        assertThatThrownBy(() -> useCase.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWritten(command);
        assertThat(logs.messages(Level.SEVERE)).singleElement().satisfies(line -> assertThat(line).contains(
                unknownTransfer.toString(), command.messageId().toString(), correlationId.toString()));
    }

    @Test
    void creditWithDifferentAmountThanDebitIsRejectedAsMismatch() {
        wallets.add(recipientId, "100000.00", 5);
        MessageEnvelope<CreditWallet> command = command(1, "150000.01");

        assertThatThrownBy(() -> useCase.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWritten(command);
        assertThat(wallets.of(recipientId).balance()).isEqualTo(Money.parse("100000.00"));
    }

    @Test
    void creditedSettlementWithoutStoredResultIsAnInvariantViolation() {
        wallets.add(recipientId, "100000.00", 5);
        settlements.rows.put(transferId, TransferSettlement.credited(transferId, NOW));

        assertThatThrownBy(() -> useCase.handle(command(2, "150000.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(transferId.toString());
    }

    @Test
    void rejectsMissingCommand() {
        assertThatThrownBy(() -> useCase.handle(null)).isInstanceOf(ContractViolationException.class);
        assertThat(transactions.count).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private MessageEnvelope<CreditWallet> command(int attempt, String amount) {
        return command(transferId, attempt, amount);
    }

    private MessageEnvelope<CreditWallet> command(UUID transfer, int attempt, String amount) {
        CreditWallet payload = new CreditWallet(transfer, recipientId, Money.parse(amount), Currency.COP, attempt);
        return new MessageEnvelope<>(UUID.randomUUID(), MessageType.CREDIT_WALLET, correlationId, transfer,
                "transfer-service", NOW.minusSeconds(1), payload);
    }

    private Object storedResult(MessageEnvelope<CreditWallet> command) {
        return processed.find(command.messageId(), WalletCommandSupport.CONSUMER)
                .map(ProcessedMessage::resultPayload).orElseThrow();
    }

    private void assertNothingWritten(MessageEnvelope<CreditWallet> command) {
        assertThat(settlements.rows).isEmpty();
        assertThat(ledger.ofType(LedgerEntryType.CREDIT)).isEmpty();
        assertThat(processed.find(command.messageId(), WalletCommandSupport.CONSUMER)).isEmpty();
        assertThat(outbox.messages).isEmpty();
    }

    private void assertEnvelopes(MessageEnvelope<CreditWallet> command) {
        outbox.messages.forEach(message -> {
            assertThat(message.envelope().correlationId()).isEqualTo(correlationId);
            assertThat(message.envelope().transferId()).isEqualTo(transferId);
            assertThat(message.envelope().producer()).isEqualTo(MessageEnvelope.WALLET_PRODUCER);
            assertThat(message.envelope().occurredAt()).isEqualTo(NOW);
            assertThat(message.envelope().messageId()).isNotEqualTo(command.messageId());
            assertThat(message.createdAt()).isEqualTo(NOW);
        });
    }

    private void assertAudit(OutboxMessage message, AttemptOutcome outcome, String reason) {
        AttemptRecorded audit = (AttemptRecorded) message.envelope().payload();
        assertThat(audit.eventId()).isEqualTo(message.envelope().messageId());
        assertThat(audit.source()).isEqualTo(AttemptSource.WALLET);
        assertThat(audit.stage()).isEqualTo(AttemptStage.CREDIT);
        assertThat(audit.outcome()).isEqualTo(outcome);
        assertThat(audit.reason()).isEqualTo(reason);
        assertThat(audit.transferId()).isEqualTo(transferId);
        assertThat(audit.userId()).isEqualTo(recipientId);
        assertThat(audit.amount()).isEqualTo(Money.parse("150000.00"));
        assertThat(audit.correlationId()).isEqualTo(correlationId);
        assertThat(audit.occurredAt()).isEqualTo(NOW);
    }
}

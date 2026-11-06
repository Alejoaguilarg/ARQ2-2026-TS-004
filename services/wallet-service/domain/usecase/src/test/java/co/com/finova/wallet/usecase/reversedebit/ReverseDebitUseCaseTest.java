package co.com.finova.wallet.usecase.reversedebit;

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
import co.com.finova.wallet.model.message.payload.DebitReversed;
import co.com.finova.wallet.model.message.payload.ReverseDebit;
import co.com.finova.wallet.model.message.payload.ReverseReason;
import co.com.finova.wallet.model.message.payload.WalletCredited;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import co.com.finova.wallet.model.settlement.SettlementOutcome;
import co.com.finova.wallet.model.settlement.TransferSettlement;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.usecase.command.CommandOutcome;
import co.com.finova.wallet.usecase.command.CommandOutcome.Settlement;
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

/** Orchestration of the reversal (spec §4.7, §4.8) with in-memory ports. */
class ReverseDebitUseCaseTest {

    private final InMemoryPorts.Wallets wallets = new InMemoryPorts.Wallets();
    private final InMemoryPorts.Ledger ledger = new InMemoryPorts.Ledger();
    private final InMemoryPorts.Settlements settlements = new InMemoryPorts.Settlements();
    private final InMemoryPorts.ProcessedMessages processed = new InMemoryPorts.ProcessedMessages();
    private final InMemoryPorts.Outbox outbox = new InMemoryPorts.Outbox();
    private final InMemoryPorts.Transactions transactions = new InMemoryPorts.Transactions();
    private final InMemoryPorts.CapturedLogs logs = new InMemoryPorts.CapturedLogs();
    private final Logger useCaseLogger = Logger.getLogger(ReverseDebitUseCase.class.getName());

    private final ReverseDebitUseCase useCase = new ReverseDebitUseCase(wallets, ledger, settlements, processed,
            outbox, transactions, new InMemoryPorts.RandomIds(), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID senderId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private Wallet sender;

    @BeforeEach
    void debitedTransfer() {
        useCaseLogger.addHandler(logs);
        sender = wallets.add(senderId, "350000.00", 1);
        ledger.debited(transferId, sender, "150000.00");
    }

    @AfterEach
    void releaseLogs() {
        useCaseLogger.removeHandler(logs);
    }

    @Test
    void appliedReversalRestoresSenderAndWritesLedgerResultBalanceChangedAndAudit() {
        MessageEnvelope<ReverseDebit> command = command(ReverseReason.CREDIT_TIMEOUT, "150000.00");

        CommandOutcome outcome = useCase.handle(command);

        assertThat(outcome).isEqualTo(new CommandOutcome(MessageType.DEBIT_REVERSED, Settlement.CLAIMED, false));
        assertThat(transactions.count).isEqualTo(1);
        assertThat(settlements.rows.get(transferId))
                .isEqualTo(new TransferSettlement(transferId, SettlementOutcome.REVERSED, NOW));
        assertThat(wallets.of(senderId).balance()).isEqualTo(Money.parse("500000.00"));
        assertThat(ledger.ofType(LedgerEntryType.REVERSAL)).singleElement().satisfies(entry -> {
            assertThat(entry.walletId()).isEqualTo(sender.walletId());
            assertThat(entry.transferId()).isEqualTo(transferId);
            assertThat(entry.amount()).isEqualTo(Money.parse("150000.00"));
            assertThat(entry.balanceAfter()).isEqualTo(Money.parse("500000.00"));
            assertThat(entry.createdAt()).isEqualTo(NOW);
        });

        DebitReversed expected = new DebitReversed(transferId, sender.walletId(), senderId,
                Money.parse("150000.00"), 2);
        assertThat(storedResult(command)).isEqualTo(expected);
        assertThat(outbox.types()).containsExactly(
                MessageType.DEBIT_REVERSED, MessageType.BALANCE_CHANGED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(expected);
        assertThat(outbox.payload(1)).isEqualTo(new BalanceChanged(sender.walletId(), senderId,
                Money.parse("500000.00"), 2));
        assertAudit(outbox.message(2), AttemptOutcome.APPLIED, null);
        assertEnvelopes(command);
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("applied", correlationId.toString(), transferId.toString()));
    }

    @Test
    void walletUpdatedAtAndBalanceChangedOccurredAtComeFromOneClockReadInMicros() {
        InMemoryPorts.SteppingClock clock = new InMemoryPorts.SteppingClock();
        ReverseDebitUseCase steppingUseCase = new ReverseDebitUseCase(wallets, ledger, settlements, processed,
                outbox, transactions, new InMemoryPorts.RandomIds(), clock);

        steppingUseCase.handle(command(ReverseReason.CREDIT_TIMEOUT, "150000.00"));

        assertThat(clock.reads).isEqualTo(1);
        assertThat(wallets.of(senderId).updatedAt()).isEqualTo(InMemoryPorts.SteppingClock.START_MICROS);
        assertThat(outbox.message(1).envelope().occurredAt()).isEqualTo(InMemoryPorts.SteppingClock.START_MICROS);
    }

    @Test
    void creditAlreadyAppliedReemitsWalletCreditedAndAuditsAlreadyCredited() {
        WalletCredited credited = new WalletCredited(transferId, UUID.randomUUID(), UUID.randomUUID(),
                Money.parse("150000.00"), 9);
        settlements.rows.put(transferId, TransferSettlement.credited(transferId, NOW.minusSeconds(30)));
        processed.save(ProcessedMessage.withResult(UUID.randomUUID(), WalletCommandSupport.CONSUMER,
                NOW.minusSeconds(30), credited));
        MessageEnvelope<ReverseDebit> command = command(ReverseReason.CREDIT_TIMEOUT, "150000.00");

        CommandOutcome outcome = useCase.handle(command);

        assertThat(outcome).isEqualTo(
                new CommandOutcome(MessageType.WALLET_CREDITED, Settlement.ALREADY_CREDITED, false));
        assertThat(wallets.of(senderId).balance()).isEqualTo(Money.parse("350000.00"));
        assertThat(ledger.ofType(LedgerEntryType.REVERSAL)).isEmpty();
        assertThat(settlements.rows.get(transferId).outcome()).isEqualTo(SettlementOutcome.CREDITED);
        assertThat(storedResult(command)).isEqualTo(credited);
        assertThat(outbox.types()).containsExactly(MessageType.WALLET_CREDITED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(credited);
        assertAudit(outbox.message(1), AttemptOutcome.REJECTED, "ALREADY_CREDITED");
        assertEnvelopes(command);
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("ALREADY_CREDITED", transferId.toString()));
    }

    @Test
    void reversalAlreadyAppliedReemitsOriginalDebitReversedWithoutAudit() {
        useCase.handle(command(ReverseReason.CREDIT_TIMEOUT, "150000.00"));
        DebitReversed original = (DebitReversed) outbox.payload(0);
        outbox.messages.clear();
        int changes = wallets.changes;
        MessageEnvelope<ReverseDebit> resent = command(ReverseReason.CREDIT_TIMEOUT, "150000.00");

        CommandOutcome outcome = useCase.handle(resent);

        assertThat(outcome).isEqualTo(
                new CommandOutcome(MessageType.DEBIT_REVERSED, Settlement.ALREADY_REVERSED, false));
        assertThat(wallets.changes).isEqualTo(changes);
        assertThat(ledger.ofType(LedgerEntryType.REVERSAL)).hasSize(1);
        assertThat(outbox.types()).containsExactly(MessageType.DEBIT_REVERSED);
        assertThat(outbox.payload(0)).isEqualTo(original);
        assertThat(storedResult(resent)).isEqualTo(original);
        assertEnvelopes(resent);
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("already reversed", transferId.toString()));
    }

    @Test
    void repeatedCommandRepublishesStoredResultOnly() {
        MessageEnvelope<ReverseDebit> command = command(ReverseReason.RECIPIENT_WALLET_NOT_FOUND, "150000.00");
        useCase.handle(command);
        Object original = outbox.payload(0);
        outbox.messages.clear();

        CommandOutcome outcome = useCase.handle(command);

        assertThat(outcome).isEqualTo(new CommandOutcome(MessageType.DEBIT_REVERSED, Settlement.NOT_APPLICABLE, true));
        assertThat(processed.saves).isEqualTo(1);
        assertThat(ledger.ofType(LedgerEntryType.REVERSAL)).hasSize(1);
        assertThat(outbox.types()).containsExactly(MessageType.DEBIT_REVERSED);
        assertThat(outbox.payload(0)).isEqualTo(original);
    }

    @Test
    void reversalWithoutDebitIsRejectedAsMismatchAndLogged() {
        UUID unknownTransfer = UUID.randomUUID();
        MessageEnvelope<ReverseDebit> command = command(unknownTransfer, senderId, "150000.00");

        assertThatThrownBy(() -> useCase.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWritten(command);
        assertThat(logs.messages(Level.SEVERE)).singleElement().satisfies(line -> assertThat(line).contains(
                unknownTransfer.toString(), command.messageId().toString(), correlationId.toString()));
    }

    @Test
    void reversalWithDifferentAmountIsRejectedAsMismatch() {
        MessageEnvelope<ReverseDebit> command = command(ReverseReason.CREDIT_TIMEOUT, "149999.99");

        assertThatThrownBy(() -> useCase.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWritten(command);
    }

    @Test
    void reversalForAnotherSenderIsRejectedAsMismatch() {
        UUID otherUser = UUID.randomUUID();
        wallets.add(otherUser, "10.00", 0);
        MessageEnvelope<ReverseDebit> command = command(transferId, otherUser, "150000.00");

        assertThatThrownBy(() -> useCase.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWritten(command);
        assertThat(wallets.of(otherUser).balance()).isEqualTo(Money.parse("10.00"));
    }

    @Test
    void reversalForUserWithoutWalletIsRejectedAsMismatch() {
        MessageEnvelope<ReverseDebit> command = command(transferId, UUID.randomUUID(), "150000.00");

        assertThatThrownBy(() -> useCase.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWritten(command);
    }

    @Test
    void reversedSettlementWithoutStoredResultIsAnInvariantViolation() {
        settlements.rows.put(transferId, TransferSettlement.reversed(transferId, NOW));

        assertThatThrownBy(() -> useCase.handle(command(ReverseReason.CREDIT_TIMEOUT, "150000.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(transferId.toString());
    }

    @Test
    void rejectsMissingCommand() {
        assertThatThrownBy(() -> useCase.handle(null)).isInstanceOf(ContractViolationException.class);
        assertThat(transactions.count).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private MessageEnvelope<ReverseDebit> command(ReverseReason reason, String amount) {
        ReverseDebit payload = new ReverseDebit(transferId, senderId, Money.parse(amount), Currency.COP, reason);
        return envelope(transferId, payload);
    }

    private MessageEnvelope<ReverseDebit> command(UUID transfer, UUID user, String amount) {
        ReverseDebit payload = new ReverseDebit(transfer, user, Money.parse(amount), Currency.COP,
                ReverseReason.CREDIT_TIMEOUT);
        return envelope(transfer, payload);
    }

    private MessageEnvelope<ReverseDebit> envelope(UUID transfer, ReverseDebit payload) {
        return new MessageEnvelope<>(UUID.randomUUID(), MessageType.REVERSE_DEBIT, correlationId, transfer,
                "transfer-service", NOW.minusSeconds(1), payload);
    }

    private Object storedResult(MessageEnvelope<ReverseDebit> command) {
        return processed.find(command.messageId(), WalletCommandSupport.CONSUMER)
                .map(ProcessedMessage::resultPayload).orElseThrow();
    }

    private void assertNothingWritten(MessageEnvelope<ReverseDebit> command) {
        assertThat(settlements.rows).isEmpty();
        assertThat(ledger.ofType(LedgerEntryType.REVERSAL)).isEmpty();
        assertThat(wallets.of(senderId).balance()).isEqualTo(Money.parse("350000.00"));
        assertThat(processed.find(command.messageId(), WalletCommandSupport.CONSUMER)).isEmpty();
        assertThat(outbox.messages).isEmpty();
    }

    private void assertEnvelopes(MessageEnvelope<ReverseDebit> command) {
        outbox.messages.forEach(message -> {
            assertThat(message.envelope().correlationId()).isEqualTo(correlationId);
            assertThat(message.envelope().transferId()).isEqualTo(transferId);
            assertThat(message.envelope().occurredAt()).isEqualTo(NOW);
            assertThat(message.envelope().messageId()).isNotEqualTo(command.messageId());
        });
    }

    private void assertAudit(OutboxMessage message, AttemptOutcome outcome, String reason) {
        AttemptRecorded audit = (AttemptRecorded) message.envelope().payload();
        assertThat(audit.eventId()).isEqualTo(message.envelope().messageId());
        assertThat(audit.source()).isEqualTo(AttemptSource.WALLET);
        assertThat(audit.stage()).isEqualTo(AttemptStage.REVERSAL);
        assertThat(audit.outcome()).isEqualTo(outcome);
        assertThat(audit.reason()).isEqualTo(reason);
        assertThat(audit.transferId()).isEqualTo(transferId);
        assertThat(audit.userId()).isEqualTo(senderId);
        assertThat(audit.amount()).isEqualTo(Money.parse("150000.00"));
        assertThat(audit.correlationId()).isEqualTo(correlationId);
        assertThat(audit.occurredAt()).isEqualTo(NOW);
    }
}

package co.com.finova.wallet.usecase.debitwallet;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.common.gateways.IdGenerator;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.LedgerEntryType;
import co.com.finova.wallet.model.ledger.gateways.LedgerRepository;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptSource;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitRejectionReason;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import co.com.finova.wallet.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;
import co.com.finova.wallet.usecase.command.CommandOutcome;
import co.com.finova.wallet.usecase.command.CommandOutcome.Settlement;
import co.com.finova.wallet.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Orchestration of the debit (spec §4.7, §4.8) with in-memory ports. Money rules (conditional update,
 * concurrency, rollback) are proven against real PostgreSQL in DebitWalletScenariosTest.
 */
class DebitWalletUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private final InMemoryWallets wallets = new InMemoryWallets();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final CountingTransactionRunner transactions = new CountingTransactionRunner();
    private final SequentialIds ids = new SequentialIds();
    private final CapturingHandler logs = new CapturingHandler();
    private final Logger useCaseLogger = Logger.getLogger(DebitWalletUseCase.class.getName());

    private final DebitWalletUseCase useCase = new DebitWalletUseCase(wallets, ledger, processed, outbox,
            transactions, ids, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID userId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void captureLogs() {
        useCaseLogger.addHandler(logs);
    }

    @AfterEach
    void releaseLogs() {
        useCaseLogger.removeHandler(logs);
    }

    @Test
    void appliedDebitWritesLedgerProcessedResultBalanceChangedAndAuditInOneTransaction() {
        Wallet wallet = wallets.add(userId, "500000.00", 3);
        MessageEnvelope<DebitWallet> command = command("150000.00");

        CommandOutcome outcome = useCase.handle(command);

        assertThat(outcome).isEqualTo(new CommandOutcome(MessageType.WALLET_DEBITED, Settlement.NOT_APPLICABLE, false));
        assertThat(transactions.count).isEqualTo(1);

        assertThat(ledger.entries).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(LedgerEntryType.DEBIT);
            assertThat(entry.walletId()).isEqualTo(wallet.walletId());
            assertThat(entry.transferId()).isEqualTo(transferId);
            assertThat(entry.amount()).isEqualTo(Money.parse("150000.00"));
            assertThat(entry.balanceAfter()).isEqualTo(Money.parse("350000.00"));
            assertThat(entry.createdAt()).isEqualTo(NOW);
        });

        WalletDebited expected = new WalletDebited(transferId, wallet.walletId(), userId,
                Money.parse("150000.00"), 4);
        ProcessedMessage stored = processed.find(command.messageId(), DebitWalletUseCase.CONSUMER).orElseThrow();
        assertThat(stored.resultPayload()).isEqualTo(expected);
        assertThat(stored.processedAt()).isEqualTo(NOW);

        assertThat(outbox.types()).containsExactly(
                MessageType.WALLET_DEBITED, MessageType.BALANCE_CHANGED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(expected);
        assertThat(outbox.payload(1)).isEqualTo(new BalanceChanged(wallet.walletId(), userId,
                Money.parse("350000.00"), 4));
        assertAudit(outbox.message(2), AttemptOutcome.APPLIED, null, "150000.00");
        outbox.messages.forEach(message -> {
            assertThat(message.envelope().correlationId()).isEqualTo(correlationId);
            assertThat(message.envelope().transferId()).isEqualTo(transferId);
            assertThat(message.envelope().producer()).isEqualTo(MessageEnvelope.WALLET_PRODUCER);
            assertThat(message.envelope().occurredAt()).isEqualTo(NOW);
            assertThat(message.envelope().messageId()).isNotEqualTo(command.messageId());
            assertThat(message.createdAt()).isEqualTo(NOW);
            assertThat(message.publishedAt()).isNull();
        });
        assertThat(outbox.messages).extracting(OutboxMessage::id).doesNotHaveDuplicates();
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("applied", correlationId.toString(), transferId.toString()));
    }

    @Test
    void walletUpdatedAtAndBalanceChangedOccurredAtComeFromOneClockReadInMicros() {
        InMemoryPorts.SteppingClock clock = new InMemoryPorts.SteppingClock();
        DebitWalletUseCase steppingUseCase = new DebitWalletUseCase(wallets, ledger, processed, outbox,
                transactions, ids, clock);
        wallets.add(userId, "500000.00", 0);

        steppingUseCase.handle(command("150000.00"));

        assertThat(clock.reads).isEqualTo(1);
        assertThat(wallets.byUser.get(userId).updatedAt()).isEqualTo(InMemoryPorts.SteppingClock.START_MICROS);
        assertThat(outbox.message(1).envelope().occurredAt()).isEqualTo(InMemoryPorts.SteppingClock.START_MICROS);
    }

    @Test
    void insufficientFundsRejectsWithoutLedgerOrBalanceChanged() {
        wallets.add(userId, "50000.00", 2);
        MessageEnvelope<DebitWallet> command = command("80000.00");

        CommandOutcome outcome = useCase.handle(command);

        assertThat(outcome).isEqualTo(new CommandOutcome(MessageType.DEBIT_REJECTED, Settlement.NOT_APPLICABLE, false));
        DebitRejected expected = new DebitRejected(transferId, userId, DebitRejectionReason.INSUFFICIENT_FUNDS);
        assertThat(ledger.entries).isEmpty();
        assertThat(wallets.byUser.get(userId).balance()).isEqualTo(Money.parse("50000.00"));
        assertThat(processed.find(command.messageId(), DebitWalletUseCase.CONSUMER))
                .get().extracting(ProcessedMessage::resultPayload).isEqualTo(expected);
        assertThat(outbox.types()).containsExactly(MessageType.DEBIT_REJECTED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(expected);
        assertAudit(outbox.message(1), AttemptOutcome.REJECTED, "INSUFFICIENT_FUNDS", "80000.00");
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("INSUFFICIENT_FUNDS", correlationId.toString(), transferId.toString()));
    }

    @Test
    void missingWalletRejectsWithSenderWalletNotFound() {
        MessageEnvelope<DebitWallet> command = command("10.00");

        useCase.handle(command);

        DebitRejected expected = new DebitRejected(transferId, userId, DebitRejectionReason.SENDER_WALLET_NOT_FOUND);
        assertThat(ledger.entries).isEmpty();
        assertThat(outbox.types()).containsExactly(MessageType.DEBIT_REJECTED, MessageType.ATTEMPT_RECORDED);
        assertThat(outbox.payload(0)).isEqualTo(expected);
        assertAudit(outbox.message(1), AttemptOutcome.REJECTED, "SENDER_WALLET_NOT_FOUND", "10.00");
    }

    @Test
    void repeatedCommandRepublishesStoredResultWithNewMessageIdAndNoAudit() {
        wallets.add(userId, "500000.00", 0);
        MessageEnvelope<DebitWallet> command = command("150000.00");
        useCase.handle(command);
        Object firstResult = outbox.payload(0);
        outbox.messages.clear();

        CommandOutcome outcome = useCase.handle(command);

        assertThat(outcome).isEqualTo(new CommandOutcome(MessageType.WALLET_DEBITED, Settlement.NOT_APPLICABLE, true));
        assertThat(transactions.count).isEqualTo(2);
        assertThat(ledger.entries).hasSize(1);
        assertThat(wallets.debitCalls).isEqualTo(1);
        assertThat(processed.saves).isEqualTo(1);
        assertThat(outbox.types()).containsExactly(MessageType.WALLET_DEBITED);
        assertThat(outbox.payload(0)).isEqualTo(firstResult);
        MessageEnvelope<?> republished = outbox.message(0).envelope();
        assertThat(republished.messageId()).isNotEqualTo(command.messageId());
        assertThat(republished.correlationId()).isEqualTo(correlationId);
        assertThat(republished.transferId()).isEqualTo(transferId);
        assertThat(republished.occurredAt()).isEqualTo(NOW);
        assertThat(logs.messages(Level.INFO)).anySatisfy(line ->
                assertThat(line).contains("repeated", correlationId.toString(), transferId.toString()));
    }

    @Test
    void repeatedRejectedCommandStaysRejectedEvenWithFundsNow() {
        Wallet wallet = wallets.add(userId, "50000.00", 0);
        MessageEnvelope<DebitWallet> command = command("80000.00");
        useCase.handle(command);
        wallets.byUser.put(userId, new Wallet(wallet.walletId(), userId, Money.parse("900000.00"),
                Currency.COP, 1, NOW));
        outbox.messages.clear();

        useCase.handle(command);

        assertThat(ledger.entries).isEmpty();
        assertThat(wallets.byUser.get(userId).balance()).isEqualTo(Money.parse("900000.00"));
        assertThat(outbox.types()).containsExactly(MessageType.DEBIT_REJECTED);
        assertThat(outbox.payload(0)).isEqualTo(
                new DebitRejected(transferId, userId, DebitRejectionReason.INSUFFICIENT_FUNDS));
    }

    @Test
    void duplicateLedgerEntryIsLoggedAsErrorAndRethrown() {
        wallets.add(userId, "500000.00", 0);
        useCase.handle(command("100.00"));
        outbox.messages.clear();
        MessageEnvelope<DebitWallet> otherCommand = command("100.00");

        assertThatThrownBy(() -> useCase.handle(otherCommand)).isInstanceOf(DuplicateLedgerEntryException.class);

        assertThat(processed.find(otherCommand.messageId(), DebitWalletUseCase.CONSUMER)).isEmpty();
        assertThat(outbox.messages).isEmpty();
        assertThat(logs.messages(Level.SEVERE)).singleElement().satisfies(line -> assertThat(line).contains(
                transferId.toString(), otherCommand.messageId().toString(), correlationId.toString()));
    }

    @Test
    void rejectsMissingCommand() {
        assertThatThrownBy(() -> useCase.handle(null)).isInstanceOf(ContractViolationException.class);
        assertThat(transactions.count).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private MessageEnvelope<DebitWallet> command(String amount) {
        DebitWallet payload = new DebitWallet(transferId, userId, Money.parse(amount), Currency.COP);
        return new MessageEnvelope<>(UUID.randomUUID(), MessageType.DEBIT_WALLET, correlationId, transferId,
                "transfer-service", NOW.minusSeconds(1), payload);
    }

    private void assertAudit(OutboxMessage message, AttemptOutcome outcome, String reason, String amount) {
        AttemptRecorded audit = (AttemptRecorded) message.envelope().payload();
        assertThat(audit.eventId()).isEqualTo(message.envelope().messageId());
        assertThat(audit.source()).isEqualTo(AttemptSource.WALLET);
        assertThat(audit.stage()).isEqualTo(AttemptStage.DEBIT);
        assertThat(audit.outcome()).isEqualTo(outcome);
        assertThat(audit.reason()).isEqualTo(reason);
        assertThat(audit.transferId()).isEqualTo(transferId);
        assertThat(audit.userId()).isEqualTo(userId);
        assertThat(audit.amount()).isEqualTo(Money.parse(amount));
        assertThat(audit.idempotencyKey()).isNull();
        assertThat(audit.statusCode()).isNull();
        assertThat(audit.correlationId()).isEqualTo(correlationId);
        assertThat(audit.occurredAt()).isEqualTo(NOW);
    }

    // ---------------------------------------------------------------- in-memory ports

    private static final class InMemoryWallets implements WalletRepository {
        private final Map<UUID, Wallet> byUser = new HashMap<>();
        private int debitCalls;

        Wallet add(UUID userId, String balance, long version) {
            Wallet wallet = new Wallet(UUID.randomUUID(), userId, Money.parse(balance), Currency.COP, version, NOW);
            byUser.put(userId, wallet);
            return wallet;
        }

        @Override
        public Optional<Wallet> findByUserId(UUID userId) {
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public Optional<Wallet> debitIfSufficient(UUID userId, Money amount, Instant updatedAt) {
            debitCalls++;
            Wallet current = byUser.get(userId);
            if (current == null || !current.balance().isGreaterOrEqual(amount)) {
                return Optional.empty();
            }
            Wallet updated = new Wallet(current.walletId(), userId, current.balance().minus(amount),
                    current.currency(), current.version() + 1, updatedAt);
            byUser.put(userId, updated);
            return Optional.of(updated);
        }

        @Override
        public Wallet increaseBalance(UUID walletId, Money amount, Instant updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Wallet> findPageAfter(UUID afterWalletId, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryLedger implements LedgerRepository {
        private final List<LedgerEntry> entries = new ArrayList<>();

        @Override
        public void append(LedgerEntry entry) {
            boolean duplicate = entries.stream().anyMatch(existing ->
                    entry.transferId().equals(existing.transferId()) && entry.type() == existing.type());
            if (duplicate) {
                throw new DuplicateLedgerEntryException(entry.transferId(), entry.type());
            }
            entries.add(entry);
        }

        @Override
        public Optional<LedgerEntry> findDebit(UUID transferId) {
            throw new UnsupportedOperationException("the debit never looks up a DEBIT");
        }
    }

    private static final class InMemoryProcessedMessages implements ProcessedMessageRepository {
        private final Map<String, ProcessedMessage> rows = new HashMap<>();
        private int saves;

        @Override
        public Optional<ProcessedMessage> find(UUID messageId, String consumer) {
            return Optional.ofNullable(rows.get(messageId + "|" + consumer));
        }

        @Override
        public void save(ProcessedMessage message) {
            saves++;
            rows.put(message.messageId() + "|" + message.consumer(), message);
        }

        @Override
        public Optional<co.com.finova.wallet.model.message.payload.MessagePayload> findStoredResult(
                UUID transferId, MessageType resultType) {
            throw new UnsupportedOperationException("the debit never looks up a stored result by transfer");
        }
    }

    private static final class InMemoryOutbox implements OutboxRepository {
        private final List<OutboxMessage> messages = new ArrayList<>();

        @Override
        public void save(OutboxMessage message) {
            messages.add(message);
        }

        List<MessageType> types() {
            return messages.stream().map(message -> message.envelope().type()).toList();
        }

        OutboxMessage message(int index) {
            return messages.get(index);
        }

        Object payload(int index) {
            return messages.get(index).envelope().payload();
        }
    }

    private static final class CountingTransactionRunner implements TransactionRunner {
        private int count;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            count++;
            return work.get();
        }
    }

    private static final class SequentialIds implements IdGenerator {
        @Override
        public UUID newId() {
            return UUID.randomUUID();
        }
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }

        List<String> messages(Level level) {
            return records.stream().filter(r -> r.getLevel().equals(level)).map(LogRecord::getMessage).toList();
        }
    }
}

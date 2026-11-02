package co.com.finova.wallet.usecase.support;

import co.com.finova.wallet.model.balance.BalanceReadModelUnavailableException;
import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.balance.gateways.BalanceReadModel;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.common.gateways.IdGenerator;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.LedgerEntryType;
import co.com.finova.wallet.model.ledger.gateways.LedgerRepository;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.CreditFailed;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitReversed;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.message.payload.WalletCredited;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import co.com.finova.wallet.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.wallet.model.settlement.TransferSettlement;
import co.com.finova.wallet.model.settlement.gateways.TransferSettlementRepository;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * In-memory ports for the use case unit tests. They check orchestration only; money rules are proven
 * against real PostgreSQL in the scenario tests of app-service.
 */
public final class InMemoryPorts {

    public static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private InMemoryPorts() {
    }

    /**
     * Balance read model with the same compare-and-set rule as the Lua script. It can fail on reads or
     * writes, and beforeWrite simulates a concurrent writer that gets in between a read and a write.
     */
    public static final class Balances implements BalanceReadModel {
        public final Map<UUID, BalanceSnapshot> byUser = new HashMap<>();
        public boolean failReads;
        public boolean failWrites;
        public int writeAttempts;
        public Runnable beforeWrite = () -> { };

        public void put(BalanceSnapshot snapshot) {
            byUser.put(snapshot.userId(), snapshot);
        }

        @Override
        public Optional<BalanceSnapshot> find(UUID userId) {
            if (failReads) {
                throw new BalanceReadModelUnavailableException("read failed", new IllegalStateException("down"));
            }
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public boolean storeIfNewer(BalanceSnapshot snapshot) {
            writeAttempts++;
            if (failWrites) {
                throw new BalanceReadModelUnavailableException("write failed", new IllegalStateException("down"));
            }
            beforeWrite.run();
            BalanceSnapshot stored = byUser.get(snapshot.userId());
            if (stored != null && snapshot.version() <= stored.version()) {
                return false;
            }
            byUser.put(snapshot.userId(), snapshot);
            return true;
        }
    }

    public static final class Wallets implements WalletRepository {
        public final Map<UUID, Wallet> byUser = new HashMap<>();
        public int changes;
        public int finds;

        public Wallet add(UUID userId, String balance, long version) {
            Wallet wallet = new Wallet(UUID.randomUUID(), userId, Money.parse(balance), Currency.COP, version, NOW);
            byUser.put(userId, wallet);
            return wallet;
        }

        public Wallet of(UUID userId) {
            return byUser.get(userId);
        }

        @Override
        public Optional<Wallet> findByUserId(UUID userId) {
            finds++;
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public Optional<Wallet> debitIfSufficient(UUID userId, Money amount, Instant updatedAt) {
            Wallet current = byUser.get(userId);
            if (current == null || !current.balance().isGreaterOrEqual(amount)) {
                return Optional.empty();
            }
            return Optional.of(replace(current, current.balance().minus(amount), updatedAt));
        }

        @Override
        public Wallet increaseBalance(UUID walletId, Money amount, Instant updatedAt) {
            Wallet current = byUser.values().stream().filter(w -> w.walletId().equals(walletId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no wallet " + walletId));
            return replace(current, current.balance().plus(amount), updatedAt);
        }

        private Wallet replace(Wallet current, Money balance, Instant updatedAt) {
            changes++;
            Wallet updated = new Wallet(current.walletId(), current.userId(), balance, current.currency(),
                    current.version() + 1, updatedAt);
            byUser.put(current.userId(), updated);
            return updated;
        }
    }

    /**
     * A clock that moves forward one nanosecond on every read and starts with sub-microsecond digits.
     * If a use case read it twice, or did not truncate to microseconds (the precision of PostgreSQL),
     * wallets.updated_at and the BalanceChanged occurredAt would differ.
     */
    public static final class SteppingClock extends Clock {
        public static final Instant START = Instant.parse("2026-11-02T15:00:00.123456789Z");
        public static final Instant START_MICROS = Instant.parse("2026-11-02T15:00:00.123456Z");

        private Instant next = START;
        public int reads;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            reads++;
            Instant current = next;
            next = next.plusNanos(1);
            return current;
        }
    }

    public static final class Ledger implements LedgerRepository {
        public final List<LedgerEntry> entries = new ArrayList<>();

        /** A DEBIT already applied for the transfer (stage 3). */
        public void debited(UUID transferId, Wallet sender, String amount) {
            Wallet after = new Wallet(sender.walletId(), sender.userId(), sender.balance(), sender.currency(),
                    sender.version(), NOW);
            entries.add(LedgerEntry.debit(UUID.randomUUID(), transferId, Money.parse(amount), after, NOW));
        }

        public List<LedgerEntry> ofType(LedgerEntryType type) {
            return entries.stream().filter(entry -> entry.type() == type).toList();
        }

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
            return entries.stream()
                    .filter(entry -> entry.type() == LedgerEntryType.DEBIT && transferId.equals(entry.transferId()))
                    .findFirst();
        }
    }

    public static final class Settlements implements TransferSettlementRepository {
        public final Map<UUID, TransferSettlement> rows = new HashMap<>();

        @Override
        public Optional<TransferSettlement> claim(TransferSettlement settlement) {
            TransferSettlement existing = rows.putIfAbsent(settlement.transferId(), settlement);
            return Optional.ofNullable(existing);
        }
    }

    public static final class ProcessedMessages implements ProcessedMessageRepository {
        public final Map<String, ProcessedMessage> rows = new LinkedHashMap<>();
        public int saves;

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
        public Optional<MessagePayload> findStoredResult(UUID transferId, MessageType resultType) {
            return rows.values().stream()
                    .filter(row -> row.resultType() == resultType)
                    .map(ProcessedMessage::resultPayload)
                    .filter(payload -> transferId.equals(transferOf(payload)))
                    .findFirst();
        }

        private static UUID transferOf(MessagePayload payload) {
            return switch (payload) {
                case WalletDebited p -> p.transferId();
                case DebitRejected p -> p.transferId();
                case WalletCredited p -> p.transferId();
                case CreditFailed p -> p.transferId();
                case DebitReversed p -> p.transferId();
                default -> null;
            };
        }
    }

    public static final class Outbox implements OutboxRepository {
        public final List<OutboxMessage> messages = new ArrayList<>();

        @Override
        public void save(OutboxMessage message) {
            messages.add(message);
        }

        public List<MessageType> types() {
            return messages.stream().map(message -> message.envelope().type()).toList();
        }

        public OutboxMessage message(int index) {
            return messages.get(index);
        }

        public MessagePayload payload(int index) {
            return messages.get(index).envelope().payload();
        }
    }

    public static final class Transactions implements TransactionRunner {
        public int count;

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            count++;
            return work.get();
        }
    }

    public static final class RandomIds implements IdGenerator {
        @Override
        public UUID newId() {
            return UUID.randomUUID();
        }
    }

    public static final class CapturedLogs extends Handler {
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

        public List<String> messages(Level level) {
            return records.stream().filter(r -> r.getLevel().equals(level)).map(LogRecord::getMessage).toList();
        }
    }
}

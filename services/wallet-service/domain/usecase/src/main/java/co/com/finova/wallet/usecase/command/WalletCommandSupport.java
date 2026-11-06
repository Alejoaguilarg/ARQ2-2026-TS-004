package co.com.finova.wallet.usecase.command;

import co.com.finova.wallet.model.common.gateways.IdGenerator;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.ledger.DebitMismatchException;
import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import co.com.finova.wallet.model.processedmessage.gateways.ProcessedMessageRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What the three saga commands (DebitWallet, CreditWallet, ReverseDebit) share (spec §4.7):
 * <ul>
 *   <li>one local transaction for the effect, processed_messages and the outbox rows;</li>
 *   <li>a repeated messageId only republishes its stored result, with a new messageId and no audit;</li>
 *   <li>every outgoing message is a Wallet envelope (§4.1) written to the outbox;</li>
 *   <li>every log line carries correlationId and transferId (§1.4).</li>
 * </ul>
 * It is not a use case: each use case creates its own instance with its own logger.
 */
public final class WalletCommandSupport {

    /** processed_messages.consumer of the commands read from wallet.commands.q. */
    public static final String CONSUMER = "wallet-commands";

    private final ProcessedMessageRepository processedMessages;
    private final OutboxRepository outbox;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final System.Logger log;

    public WalletCommandSupport(ProcessedMessageRepository processedMessages, OutboxRepository outbox,
                                TransactionRunner transactions, IdGenerator ids, Clock clock, System.Logger log) {
        this.processedMessages = processedMessages;
        this.outbox = outbox;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Runs the command in one transaction and returns what it did (for the listener span only).
     * A ledger conflict or a missing DEBIT rolls everything back, is logged as an error and is rethrown;
     * the consumer sends such a command straight to the DLQ (decision S2: retrying it can never succeed).
     */
    public <P extends MessagePayload> CommandOutcome run(MessageEnvelope<P> command, UUID transferId,
                                                         Consumer<Context> handler) {
        try {
            return transactions.inTransaction(() -> execute(command, transferId, handler));
        } catch (DuplicateLedgerEntryException | DebitMismatchException e) {
            log.log(System.Logger.Level.ERROR, () -> command.type().contractName() + " rejected: " + e.getMessage()
                    + " correlationId=" + command.correlationId()
                    + " transferId=" + transferId
                    + " messageId=" + command.messageId(), e);
            throw e;
        }
    }

    /** The DEBIT of the transfer with the same amount, or a {@link DebitMismatchException}. */
    public static LedgerEntry requireDebit(Optional<LedgerEntry> debit, UUID transferId, Money amount) {
        LedgerEntry entry = debit.orElseThrow(() -> new DebitMismatchException(transferId, "the transfer was never debited"));
        if (!entry.amount().equals(amount)) {
            throw new DebitMismatchException(transferId,
                    "the DEBIT was " + entry.amount() + " but the command says " + amount);
        }
        return entry;
    }

    private <P extends MessagePayload> CommandOutcome execute(MessageEnvelope<P> command, UUID transferId,
                                                              Consumer<Context> handler) {
        // One clock read per transaction, truncated to the microseconds PostgreSQL stores: wallets.updated_at
        // and every occurredAt of the transaction (BalanceChanged included) are then exactly the same instant.
        Context context = new Context(command, transferId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        Optional<ProcessedMessage> processed = processedMessages.find(command.messageId(), CONSUMER);
        if (processed.isPresent()) {
            MessagePayload storedResult = processed.get().resultPayload();
            context.publish(storedResult);
            context.log(command.type().contractName() + " repeated: stored "
                    + storedResult.type().contractName() + " republished");
            return new CommandOutcome(storedResult.type(), CommandOutcome.Settlement.NOT_APPLICABLE, true);
        }
        handler.accept(context);
        return new CommandOutcome(context.result, context.settlement, false);
    }

    /** The command being handled, inside its transaction. */
    public final class Context {

        private final MessageEnvelope<?> command;
        private final UUID transferId;
        private final Instant now;
        private MessageType result;
        private CommandOutcome.Settlement settlement = CommandOutcome.Settlement.NOT_APPLICABLE;

        private Context(MessageEnvelope<?> command, UUID transferId, Instant now) {
            this.command = command;
            this.transferId = transferId;
            this.now = now;
        }

        public Instant now() {
            return now;
        }

        public UUID newId() {
            return ids.newId();
        }

        /** Stores the result of the command in processed_messages and writes it to the outbox. */
        public void reply(MessagePayload result) {
            processedMessages.save(ProcessedMessage.withResult(command.messageId(), CONSUMER, now, result));
            publish(result);
            this.result = result.type();
        }

        /** What happened to transfer_settlements; only reported in the outcome (NOT_APPLICABLE by default). */
        public void settled(CommandOutcome.Settlement settlement) {
            this.settlement = settlement;
        }

        /** Writes a message to the outbox in a new Wallet envelope. */
        public void publish(MessagePayload payload) {
            publish(ids.newId(), payload);
        }

        public void audit(AttemptStage stage, AttemptOutcome outcome, String reason, UUID userId, Money amount) {
            UUID eventId = ids.newId();
            publish(eventId, AttemptRecorded.fromWallet(eventId, stage, outcome, reason, transferId, userId, amount,
                    command.correlationId(), now));
        }

        /** The result stored when the transfer was settled; it always exists, so its absence is a bug. */
        public MessagePayload storedResult(MessageType resultType) {
            return processedMessages.findStoredResult(transferId, resultType)
                    .orElseThrow(() -> new IllegalStateException("no stored " + resultType.contractName()
                            + " for settled transfer " + transferId));
        }

        public void log(String message) {
            log.log(System.Logger.Level.INFO, () -> message
                    + " correlationId=" + command.correlationId()
                    + " transferId=" + transferId);
        }

        private void publish(UUID messageId, MessagePayload payload) {
            MessageEnvelope<MessagePayload> envelope = MessageEnvelope.create(messageId, command.correlationId(),
                    transferId, now, payload);
            outbox.save(OutboxMessage.of(envelope, now));
        }
    }
}

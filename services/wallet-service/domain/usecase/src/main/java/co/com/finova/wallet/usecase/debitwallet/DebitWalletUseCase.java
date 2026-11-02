package co.com.finova.wallet.usecase.debitwallet;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.gateways.IdGenerator;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.gateways.LedgerRepository;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptRecorded;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitRejectionReason;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.outbox.OutboxMessage;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import co.com.finova.wallet.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Debit of the sender's wallet (spec §4.7, §4.8). Everything happens in one local transaction: the
 * conditional update, the DEBIT ledger entry, the processed_messages row with its result and the outbox
 * rows. A repeated command is never applied again: its stored result is republished.
 */
public class DebitWalletUseCase {

    /** processed_messages.consumer of the commands read from wallet.commands.q. */
    public static final String CONSUMER = "wallet-commands";

    private static final System.Logger LOG = System.getLogger(DebitWalletUseCase.class.getName());

    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final ProcessedMessageRepository processedMessages;
    private final OutboxRepository outbox;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;

    public DebitWalletUseCase(WalletRepository wallets, LedgerRepository ledger,
                              ProcessedMessageRepository processedMessages, OutboxRepository outbox,
                              TransactionRunner transactions, IdGenerator ids, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.processedMessages = processedMessages;
        this.outbox = outbox;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
    }

    public void handle(MessageEnvelope<DebitWallet> command) {
        Contract.requireNonNull(command, "command");
        try {
            transactions.inTransaction(() -> {
                process(command);
                return null;
            });
        } catch (DuplicateLedgerEntryException e) {
            // A new messageId for a transfer already debited: the rollback undid the balance change.
            LOG.log(System.Logger.Level.ERROR, () -> "DebitWallet rejected: transfer already debited"
                    + " correlationId=" + command.correlationId()
                    + " transferId=" + command.payload().transferId()
                    + " messageId=" + command.messageId(), e);
            throw e;
        }
    }

    private void process(MessageEnvelope<DebitWallet> command) {
        DebitWallet debit = command.payload();
        Instant now = clock.instant();

        Optional<ProcessedMessage> processed = processedMessages.find(command.messageId(), CONSUMER);
        if (processed.isPresent()) {
            MessagePayload storedResult = processed.get().resultPayload();
            publish(command, storedResult, now);
            log(command, "DebitWallet repeated: stored " + storedResult.type().contractName() + " republished");
            return;
        }

        Optional<Wallet> debited = wallets.debitIfSufficient(debit.userId(), debit.amount());
        if (debited.isPresent()) {
            Wallet walletAfter = debited.get();
            ledger.append(LedgerEntry.debit(ids.newId(), debit.transferId(), debit.amount(), walletAfter, now));
            WalletDebited result = WalletDebited.from(debit.transferId(), debit.amount(), walletAfter);
            processedMessages.save(ProcessedMessage.withResult(command.messageId(), CONSUMER, now, result));
            publish(command, result, now);
            publish(command, BalanceChanged.from(walletAfter), now);
            publishAudit(command, AttemptOutcome.APPLIED, null, now);
            log(command, "DebitWallet applied: version=" + walletAfter.version());
            return;
        }

        // 0 rows updated. Reading the wallet here only names the reason; it never authorizes anything.
        DebitRejectionReason reason = wallets.findByUserId(debit.userId()).isPresent()
                ? DebitRejectionReason.INSUFFICIENT_FUNDS
                : DebitRejectionReason.SENDER_WALLET_NOT_FOUND;
        DebitRejected result = new DebitRejected(debit.transferId(), debit.userId(), reason);
        processedMessages.save(ProcessedMessage.withResult(command.messageId(), CONSUMER, now, result));
        publish(command, result, now);
        publishAudit(command, AttemptOutcome.REJECTED, reason.name(), now);
        log(command, "DebitWallet rejected: " + reason);
    }

    private void publish(MessageEnvelope<DebitWallet> command, MessagePayload payload, Instant now) {
        publish(command, ids.newId(), payload, now);
    }

    private void publish(MessageEnvelope<DebitWallet> command, UUID messageId, MessagePayload payload,
                         Instant now) {
        MessageEnvelope<MessagePayload> envelope = MessageEnvelope.create(messageId, command.correlationId(),
                command.payload().transferId(), now, payload);
        outbox.save(OutboxMessage.of(envelope, now));
    }

    private void publishAudit(MessageEnvelope<DebitWallet> command, AttemptOutcome outcome, String reason,
                              Instant now) {
        DebitWallet debit = command.payload();
        UUID eventId = ids.newId();
        AttemptRecorded audit = AttemptRecorded.fromWallet(eventId, AttemptStage.DEBIT, outcome, reason,
                debit.transferId(), debit.userId(), debit.amount(), command.correlationId(), now);
        publish(command, eventId, audit, now);
    }

    private static void log(MessageEnvelope<DebitWallet> command, String message) {
        LOG.log(System.Logger.Level.INFO, () -> message
                + " correlationId=" + command.correlationId()
                + " transferId=" + command.payload().transferId());
    }
}

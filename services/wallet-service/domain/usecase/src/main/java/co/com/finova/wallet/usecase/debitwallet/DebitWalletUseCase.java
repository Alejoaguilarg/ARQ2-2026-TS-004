package co.com.finova.wallet.usecase.debitwallet;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.gateways.IdGenerator;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.gateways.LedgerRepository;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.DebitRejected;
import co.com.finova.wallet.model.message.payload.DebitRejectionReason;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.WalletDebited;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import co.com.finova.wallet.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;
import co.com.finova.wallet.usecase.command.WalletCommandSupport;

import java.time.Clock;
import java.util.Optional;

/**
 * Debit of the sender's wallet (spec §4.7, §4.8). Everything happens in one local transaction: the
 * conditional update, the DEBIT ledger entry, the processed_messages row with its result and the outbox
 * rows. A repeated command is never applied again: its stored result is republished.
 */
public class DebitWalletUseCase {

    /** processed_messages.consumer of the commands read from wallet.commands.q. */
    public static final String CONSUMER = WalletCommandSupport.CONSUMER;

    private static final System.Logger LOG = System.getLogger(DebitWalletUseCase.class.getName());

    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final WalletCommandSupport support;

    public DebitWalletUseCase(WalletRepository wallets, LedgerRepository ledger,
                              ProcessedMessageRepository processedMessages, OutboxRepository outbox,
                              TransactionRunner transactions, IdGenerator ids, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.support = new WalletCommandSupport(processedMessages, outbox, transactions, ids, clock, LOG);
    }

    public void handle(MessageEnvelope<DebitWallet> command) {
        Contract.requireNonNull(command, "command");
        DebitWallet debit = command.payload();
        support.run(command, debit.transferId(), context -> debit(context, debit));
    }

    private void debit(WalletCommandSupport.Context context, DebitWallet debit) {
        Optional<Wallet> debited = wallets.debitIfSufficient(debit.userId(), debit.amount());
        if (debited.isPresent()) {
            Wallet walletAfter = debited.get();
            ledger.append(LedgerEntry.debit(context.newId(), debit.transferId(), debit.amount(), walletAfter,
                    context.now()));
            context.reply(WalletDebited.from(debit.transferId(), debit.amount(), walletAfter));
            context.publish(BalanceChanged.from(walletAfter));
            context.audit(AttemptStage.DEBIT, AttemptOutcome.APPLIED, null, debit.userId(), debit.amount());
            context.log("DebitWallet applied: version=" + walletAfter.version());
            return;
        }

        // 0 rows updated. Reading the wallet here only names the reason; it never authorizes anything.
        DebitRejectionReason reason = wallets.findByUserId(debit.userId()).isPresent()
                ? DebitRejectionReason.INSUFFICIENT_FUNDS
                : DebitRejectionReason.SENDER_WALLET_NOT_FOUND;
        context.reply(new DebitRejected(debit.transferId(), debit.userId(), reason));
        context.audit(AttemptStage.DEBIT, AttemptOutcome.REJECTED, reason.name(), debit.userId(), debit.amount());
        context.log("DebitWallet rejected: " + reason);
    }
}

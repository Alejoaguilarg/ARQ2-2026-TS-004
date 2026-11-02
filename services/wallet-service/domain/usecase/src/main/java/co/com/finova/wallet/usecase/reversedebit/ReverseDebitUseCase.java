package co.com.finova.wallet.usecase.reversedebit;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.gateways.IdGenerator;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.ledger.DebitMismatchException;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.gateways.LedgerRepository;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.DebitReversed;
import co.com.finova.wallet.model.message.payload.ReverseDebit;
import co.com.finova.wallet.model.outbox.gateways.OutboxRepository;
import co.com.finova.wallet.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.wallet.model.settlement.SettlementOutcome;
import co.com.finova.wallet.model.settlement.TransferSettlement;
import co.com.finova.wallet.model.settlement.gateways.TransferSettlementRepository;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;
import co.com.finova.wallet.usecase.command.WalletCommandSupport;

import java.time.Clock;
import java.util.Optional;

/**
 * Reversal of a debit (spec §4.7, §4.8). It claims transfer_settlements as REVERSED; finding CREDITED
 * means the credit was already applied, so nothing is reversed. Only the DEBIT taken from this sender,
 * with the same amount, can be reversed.
 */
public class ReverseDebitUseCase {

    private static final String ALREADY_CREDITED = "ALREADY_CREDITED";
    private static final System.Logger LOG = System.getLogger(ReverseDebitUseCase.class.getName());

    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final TransferSettlementRepository settlements;
    private final WalletCommandSupport support;

    public ReverseDebitUseCase(WalletRepository wallets, LedgerRepository ledger,
                               TransferSettlementRepository settlements, ProcessedMessageRepository processedMessages,
                               OutboxRepository outbox, TransactionRunner transactions, IdGenerator ids, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.settlements = settlements;
        this.support = new WalletCommandSupport(processedMessages, outbox, transactions, ids, clock, LOG);
    }

    public void handle(MessageEnvelope<ReverseDebit> command) {
        Contract.requireNonNull(command, "command");
        ReverseDebit reversal = command.payload();
        support.run(command, reversal.transferId(), context -> reverse(context, reversal));
    }

    private void reverse(WalletCommandSupport.Context context, ReverseDebit reversal) {
        LedgerEntry debit = WalletCommandSupport.requireDebit(ledger.findDebit(reversal.transferId()),
                reversal.transferId(), reversal.amount());
        Wallet sender = wallets.findByUserId(reversal.userId())
                .filter(wallet -> wallet.walletId().equals(debit.walletId()))
                .orElseThrow(() -> new DebitMismatchException(reversal.transferId(),
                        "the DEBIT was not taken from the wallet of user " + reversal.userId()));

        Optional<TransferSettlement> winner = settlements.claim(
                TransferSettlement.reversed(reversal.transferId(), context.now()));
        if (winner.isEmpty()) {
            Wallet walletAfter = wallets.increaseBalance(sender.walletId(), reversal.amount());
            ledger.append(LedgerEntry.reversal(context.newId(), reversal.transferId(), reversal.amount(), walletAfter,
                    context.now()));
            context.reply(DebitReversed.from(reversal.transferId(), reversal.amount(), walletAfter));
            context.publish(BalanceChanged.from(walletAfter));
            context.audit(AttemptStage.REVERSAL, AttemptOutcome.APPLIED, null, reversal.userId(), reversal.amount());
            context.log("ReverseDebit applied: reason=" + reversal.reason() + " version=" + walletAfter.version());
        } else if (winner.get().outcome() == SettlementOutcome.CREDITED) {
            // The credit won: no reversal; the original WalletCredited is re-emitted and the transfer completes.
            context.reply(context.storedResult(MessageType.WALLET_CREDITED));
            context.audit(AttemptStage.REVERSAL, AttemptOutcome.REJECTED, ALREADY_CREDITED, reversal.userId(),
                    reversal.amount());
            context.log("ReverseDebit rejected: " + ALREADY_CREDITED + ", original WalletCredited re-emitted");
        } else {
            // Already reversed: nothing is applied and nothing is audited (decision taken with the team lead).
            context.reply(context.storedResult(MessageType.DEBIT_REVERSED));
            context.log("ReverseDebit: transfer already reversed, original DebitReversed re-emitted");
        }
    }
}

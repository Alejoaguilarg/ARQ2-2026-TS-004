package co.com.finova.wallet.usecase.creditwallet;

import co.com.finova.wallet.model.common.Contract;
import co.com.finova.wallet.model.common.gateways.IdGenerator;
import co.com.finova.wallet.model.common.gateways.TransactionRunner;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.gateways.LedgerRepository;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.AttemptOutcome;
import co.com.finova.wallet.model.message.payload.AttemptStage;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.CreditFailed;
import co.com.finova.wallet.model.message.payload.CreditFailureReason;
import co.com.finova.wallet.model.message.payload.CreditWallet;
import co.com.finova.wallet.model.message.payload.WalletCredited;
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
 * Credit of the recipient's wallet (spec §4.7, §4.8). The credit and the reversal both claim the
 * transfer_settlements row; the first one wins, so a transfer is never both credited and reversed.
 * A transfer without a matching DEBIT is never credited.
 */
public class CreditWalletUseCase {

    private static final System.Logger LOG = System.getLogger(CreditWalletUseCase.class.getName());

    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final TransferSettlementRepository settlements;
    private final WalletCommandSupport support;

    public CreditWalletUseCase(WalletRepository wallets, LedgerRepository ledger,
                               TransferSettlementRepository settlements, ProcessedMessageRepository processedMessages,
                               OutboxRepository outbox, TransactionRunner transactions, IdGenerator ids, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.settlements = settlements;
        this.support = new WalletCommandSupport(processedMessages, outbox, transactions, ids, clock, LOG);
    }

    public void handle(MessageEnvelope<CreditWallet> command) {
        Contract.requireNonNull(command, "command");
        CreditWallet credit = command.payload();
        support.run(command, credit.transferId(), context -> credit(context, credit));
    }

    private void credit(WalletCommandSupport.Context context, CreditWallet credit) {
        WalletCommandSupport.requireDebit(ledger.findDebit(credit.transferId()), credit.transferId(), credit.amount());

        Optional<Wallet> recipient = wallets.findByUserId(credit.userId());
        if (recipient.isEmpty()) {
            fail(context, credit, CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND);
            return;
        }

        Optional<TransferSettlement> winner = settlements.claim(
                TransferSettlement.credited(credit.transferId(), context.now()));
        if (winner.isEmpty()) {
            Wallet walletAfter = wallets.increaseBalance(recipient.get().walletId(), credit.amount());
            ledger.append(LedgerEntry.credit(context.newId(), credit.transferId(), credit.amount(), walletAfter,
                    context.now()));
            context.reply(WalletCredited.from(credit.transferId(), credit.amount(), walletAfter));
            context.publish(BalanceChanged.from(walletAfter));
            context.audit(AttemptStage.CREDIT, AttemptOutcome.APPLIED, null, credit.userId(), credit.amount());
            context.log("CreditWallet applied: attempt=" + credit.attempt() + " version=" + walletAfter.version());
        } else if (winner.get().outcome() == SettlementOutcome.CREDITED) {
            // A later attempt: nothing is applied and nothing is audited; the original result is re-emitted.
            context.reply(context.storedResult(MessageType.WALLET_CREDITED));
            context.log("CreditWallet attempt=" + credit.attempt() + ": transfer already credited,"
                    + " original WalletCredited re-emitted");
        } else {
            fail(context, credit, CreditFailureReason.TRANSFER_REVERSED);
        }
    }

    private static void fail(WalletCommandSupport.Context context, CreditWallet credit, CreditFailureReason reason) {
        context.reply(new CreditFailed(credit.transferId(), credit.userId(), credit.attempt(), reason));
        context.audit(AttemptStage.CREDIT, AttemptOutcome.FAILED, reason.name(), credit.userId(), credit.amount());
        context.log("CreditWallet failed: " + reason + " attempt=" + credit.attempt());
    }
}

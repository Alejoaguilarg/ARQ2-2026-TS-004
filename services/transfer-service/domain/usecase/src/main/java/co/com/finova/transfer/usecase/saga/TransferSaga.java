package co.com.finova.transfer.usecase.saga;

import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.common.gateways.TransactionRunner;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.CreditFailed;
import co.com.finova.transfer.model.message.payload.CreditFailureReason;
import co.com.finova.transfer.model.message.payload.DebitRejected;
import co.com.finova.transfer.model.message.payload.DebitReversed;
import co.com.finova.transfer.model.message.payload.WalletCredited;
import co.com.finova.transfer.model.message.payload.WalletDebited;
import co.com.finova.transfer.model.message.payload.WalletResult;
import co.com.finova.transfer.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Unified saga orchestrator (spec §4.6): creates transfers, applies wallet results, and handles timeouts.
 * Every method runs in one transaction with the transfer row locked, so the consumer and the sweeper never
 * change the same transfer at the same time. Deduplicates on messageId per spec §4.1.
 */
public class TransferSaga {

    private static final String CONSUMER = "transfer-wallet-events";
    private static final int ACCEPTED = 202;

    private final TransferRepository transfers;
    private final ProcessedMessageRepository processedMessages;
    private final SagaOutbox saga;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;

    public TransferSaga(TransferRepository transfers,
                        ProcessedMessageRepository processedMessages,
                        SagaOutbox saga,
                        TransactionRunner transactions,
                        IdGenerator ids,
                        Clock clock) {
        this.transfers = transfers;
        this.processedMessages = processedMessages;
        this.saga = saga;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
    }

    /** §4.6 rule 1: create PENDING transfer, persist it, send DebitWallet to the sender's wallet. */
    public void createTransfer(TransferRequest request, UUID correlationId) {
        transactions.inTransaction(() -> {
            Instant now = SagaClock.now(clock);
            UUID transferId = ids.newId();
            UUID debitCommandId = ids.newId();
            Transfer transfer = Transfer.create(request, transferId, correlationId, debitCommandId, now);
            transfers.insert(transfer);
            saga.sendCommand(transfer, now);
            saga.audit(transfer, AttemptStage.REQUEST, AttemptOutcome.ACCEPTED, null,
                       transfer.senderUserId(), ACCEPTED, now);
            return null;
        });
    }

    /** §4.6 rule 2: WalletDebited → DEBITED; send CreditWallet(attempt 1) to the recipient. */
    public void onWalletDebited(WalletDebited event) {
        applyResult(event, (transfer, now) -> {
            if (transfer.status() != TransferStatus.PENDING) {
                return new Transition.Result(null, false, false, null);
            }
            Transfer next = transfer.debited(ids.newId(), now);
            return new Transition.Result(next, true, false, null);
        });
    }

    /** §4.6 rule 3: DebitRejected → REJECTED (terminal); publish TransferRejected. */
    public void onDebitRejected(DebitRejected event) {
        applyResult(event, (transfer, now) -> {
            if (transfer.status() != TransferStatus.PENDING) {
                return new Transition.Result(null, false, false, null);
            }
            Transfer next = transfer.rejected(FailureReason.of(event.reason()), now);
            return new Transition.Result(next, false, true, new Transition.Audit(
                AttemptStage.TRANSFER, AttemptOutcome.REJECTED, event.reason().name(),
                next.senderUserId(), null));
        });
    }

    /** §4.6 rule 4: WalletCredited in DEBITED or COMPENSATING → COMPLETED (terminal); publish TransferCompleted. */
    public void onWalletCredited(WalletCredited event) {
        applyResult(event, (transfer, now) -> {
            if (transfer.status() != TransferStatus.DEBITED && transfer.status() != TransferStatus.COMPENSATING) {
                return new Transition.Result(null, false, false, null);
            }
            Transfer next = transfer.completed(now);
            return new Transition.Result(next, false, true, new Transition.Audit(
                AttemptStage.TRANSFER, AttemptOutcome.COMPLETED, null, next.senderUserId(), null));
        });
    }

    /**
     * §4.6 rules 5 and 6: CreditFailed → audit CREDIT/FAILED; retry CreditWallet with attempt + 1,
     * or move to COMPENSATING and send ReverseDebit after 3 attempts.
     */
    public void onCreditFailed(CreditFailed event) {
        transactions.inTransaction(() -> {
            Instant now = SagaClock.now(clock);
            if (!processedMessages.markProcessed(event.transferId(), CONSUMER, now)) {
                return null;
            }
            Transfer transfer = transfers.lockById(event.transferId())
                .orElseThrow(() -> new IllegalStateException("Unknown transfer: " + event.transferId()));
            if (transfer.status() != TransferStatus.DEBITED) {
                return null;
            }
            if (event.reason() == CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND) {
                Transfer next = transfer.compensating(FailureReason.RECIPIENT_WALLET_NOT_FOUND, ids.newId(), now);
                transfers.update(next);
                saga.sendCommand(next, now);
            } else {
                saga.audit(transfer, AttemptStage.CREDIT, AttemptOutcome.FAILED, event.reason().name(),
                           transfer.recipientUserId(), null, now);
                if (transfer.canRetryCredit()) {
                    Transfer next = transfer.creditRetried(ids.newId(), now);
                    transfers.update(next);
                    saga.sendCommand(next, now);
                } else {
                    Transfer next = transfer.compensating(FailureReason.CREDIT_TIMEOUT, ids.newId(), now);
                    transfers.update(next);
                    saga.sendCommand(next, now);
                }
            }
            return null;
        });
    }

    /** §4.6 rule 7: DebitReversed → REVERSED (terminal); publish TransferReversed. */
    public void onDebitReversed(DebitReversed event) {
        applyResult(event, (transfer, now) -> {
            if (transfer.status() != TransferStatus.COMPENSATING) {
                return new Transition.Result(null, false, false, null);
            }
            Transfer next = transfer.reversed(now);
            return new Transition.Result(next, false, true, new Transition.Audit(
                AttemptStage.TRANSFER, AttemptOutcome.COMPENSATED, next.failureReason().name(),
                next.senderUserId(), null));
        });
    }

    /**
     * Marks the message as processed, locks the transfer row, applies the transition, and ignores the result
     * if the current status does not expect it (spec §4.6 rule 9).
     */
    private void applyResult(WalletResult event, Transition transition) {
        transactions.inTransaction(() -> {
            Instant now = SagaClock.now(clock);
            if (!processedMessages.markProcessed(event.transferId(), CONSUMER, now)) {
                return null;
            }
            Transfer transfer = transfers.lockById(event.transferId())
                .orElseThrow(() -> new IllegalStateException("Unknown transfer: " + event.transferId()));
            Transition.Result result = transition.apply(transfer, now);
            if (result.transfer() != null && result.transfer() != transfer) {
                transfers.update(result.transfer());
            }
            if (result.sendCommand() && result.transfer() != null) {
                saga.sendCommand(result.transfer(), now);
            }
            if (result.publishOutcome() && result.transfer() != null) {
                saga.publishOutcome(result.transfer(), now);
            }
            if (result.audit() != null && result.transfer() != null) {
                Transition.Audit audit = result.audit();
                saga.audit(result.transfer(), audit.stage(), audit.outcome(), audit.reason(),
                           audit.userId(), audit.statusCode(), now);
            }
            return null;
        });
    }

    /** A transition applied to a transfer under the row lock. */
    @FunctionalInterface
    private interface Transition {

        /** Returns the next state plus side-effect flags. */
        Result apply(Transfer transfer, Instant now);

        /** Result of a transition: next state plus whether to send command / publish outcome. */
        record Result(Transfer transfer, boolean sendCommand, boolean publishOutcome, Audit audit) {
        }

        /** Audit side effect. */
        record Audit(AttemptStage stage, AttemptOutcome outcome, String reason, UUID userId, Integer statusCode) {
        }
    }
}

package co.com.finova.transfer.usecase.handlewalletresult;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.common.gateways.TransactionRunner;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.CreditFailed;
import co.com.finova.transfer.model.message.payload.CreditFailureReason;
import co.com.finova.transfer.model.message.payload.DebitRejected;
import co.com.finova.transfer.model.message.payload.DebitReversed;
import co.com.finova.transfer.model.message.payload.MessagePayload;
import co.com.finova.transfer.model.message.payload.WalletCredited;
import co.com.finova.transfer.model.message.payload.WalletDebited;
import co.com.finova.transfer.model.message.payload.WalletResult;
import co.com.finova.transfer.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import co.com.finova.transfer.usecase.saga.SagaClock;
import co.com.finova.transfer.usecase.saga.SagaOutbox;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Applies a Wallet result to its transfer (spec §4.6), in one transaction with its processed_messages row and
 * the messages it causes:
 * <ul>
 *   <li>rule 2: WalletDebited → DEBITED, CreditWallet(attempt 1) to the recipient;</li>
 *   <li>rule 3: DebitRejected → REJECTED, TransferRejected;</li>
 *   <li>rule 4: WalletCredited in DEBITED or COMPENSATING → COMPLETED, TransferCompleted;</li>
 *   <li>rule 6: CreditFailed(RECIPIENT_WALLET_NOT_FOUND) → COMPENSATING, ReverseDebit;</li>
 *   <li>rule 7: DebitReversed → REVERSED, TransferReversed;</li>
 *   <li>rule 9: a result the current status does not expect (late, duplicate, or CreditFailed(TRANSFER_REVERSED))
 *       is acknowledged and changes nothing.</li>
 * </ul>
 * The row is locked first, so the timeout sweeper never changes the same transfer at the same time.
 */
public class HandleWalletResultUseCase {

    /** processed_messages.consumer of transfer.wallet-events.q. */
    public static final String CONSUMER = "transfer-wallet-events";

    private static final System.Logger LOG = System.getLogger(HandleWalletResultUseCase.class.getName());

    private final TransferRepository transfers;
    private final ProcessedMessageRepository processedMessages;
    private final SagaOutbox saga;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;

    public HandleWalletResultUseCase(TransferRepository transfers, ProcessedMessageRepository processedMessages,
                                     SagaOutbox saga, TransactionRunner transactions, IdGenerator ids, Clock clock) {
        this.transfers = transfers;
        this.processedMessages = processedMessages;
        this.saga = saga;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
    }

    public void handle(MessageEnvelope<?> envelope) {
        if (!(envelope.payload() instanceof WalletResult result)) {
            throw new ContractViolationException(envelope.type().contractName() + " is not a Wallet result");
        }
        transactions.inTransaction(() -> {
            apply(envelope, result);
            return null;
        });
    }

    private void apply(MessageEnvelope<?> envelope, WalletResult result) {
        Instant now = SagaClock.now(clock);
        String name = envelope.type().contractName();
        if (!processedMessages.markProcessed(envelope.messageId(), CONSUMER, now)) {
            log(envelope, result.transferId(), name + " already processed (same messageId): ignored");
            return;
        }
        // The command was published after its transfer committed, so an unknown transfer is never a race.
        Transfer transfer = transfers.lockById(result.transferId()).orElseThrow(() ->
                new ContractViolationException(name + " for unknown transfer " + result.transferId()));
        Transfer next = next(transfer, (MessagePayload) result, now);
        if (next == null) {
            log(envelope, transfer.transferId(), name + " not expected in " + transfer.status() + ": ignored");
            return;
        }
        transfers.update(next);
        log(envelope, transfer.transferId(), name + ": " + transfer.status() + " -> " + next.status());
    }

    /** The transfer after the result, or null when the current status ignores it (rule 9). */
    private Transfer next(Transfer transfer, MessagePayload result, Instant now) {
        TransferStatus status = transfer.status();
        return switch (result) {
            case WalletDebited debited when status == TransferStatus.PENDING -> {
                Transfer next = transfer.debited(ids.newId(), now);
                saga.sendCommand(next, now);
                yield next;
            }
            case DebitRejected rejected when status == TransferStatus.PENDING -> {
                Transfer next = transfer.rejected(FailureReason.of(rejected.reason()), now);
                finish(next, AttemptOutcome.REJECTED, rejected.reason().name(), now);
                yield next;
            }
            case WalletCredited credited when status == TransferStatus.DEBITED
                    || status == TransferStatus.COMPENSATING -> {
                Transfer next = transfer.completed(now);
                finish(next, AttemptOutcome.COMPLETED, null, now);
                yield next;
            }
            case CreditFailed failed when status == TransferStatus.DEBITED
                    && failed.reason() == CreditFailureReason.RECIPIENT_WALLET_NOT_FOUND -> {
                Transfer next = transfer.compensating(FailureReason.RECIPIENT_WALLET_NOT_FOUND, ids.newId(), now);
                saga.sendCommand(next, now);
                yield next;
            }
            case DebitReversed reversed when status == TransferStatus.COMPENSATING -> {
                Transfer next = transfer.reversed(now);
                // Spec §4.8: COMPENSATED is the audit name of REVERSED.
                finish(next, AttemptOutcome.COMPENSATED, next.failureReason().name(), now);
                yield next;
            }
            default -> null;
        };
    }

    // A terminal state: the outcome for Notification and the TRANSFER-stage audit record (§4.8).
    private void finish(Transfer transfer, AttemptOutcome outcome, String reason, Instant now) {
        saga.publishOutcome(transfer, now);
        saga.audit(transfer, AttemptStage.TRANSFER, outcome, reason, transfer.senderUserId(), null, now);
    }

    private static void log(MessageEnvelope<?> envelope, UUID transferId, String message) {
        LOG.log(System.Logger.Level.INFO, () -> message
                + " messageId=" + envelope.messageId()
                + " correlationId=" + envelope.correlationId()
                + " transferId=" + transferId);
    }
}

package co.com.finova.transfer.usecase.sweeptimeouts;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.common.gateways.TransactionRunner;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.outbox.gateways.OutboxRepository;
import co.com.finova.transfer.model.transfer.FailureReason;
import co.com.finova.transfer.model.transfer.SagaTrigger;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferStatus;
import co.com.finova.transfer.model.transfer.gateways.SagaTelemetry;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import co.com.finova.transfer.usecase.saga.SagaClock;
import co.com.finova.transfer.usecase.saga.SagaOutbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The timeout sweeper (spec §4.6 rule 8), run every 2 s. For each non-terminal transfer whose last command waited
 * too long:
 * <ul>
 *   <li>PENDING: the original DebitWallet is republished (same outbox row and messageId); nothing was debited, so
 *       there is no compensation;</li>
 *   <li>DEBITED (rule 5): the attempt is audited as CREDIT / FAILED (CREDIT_TIMEOUT); a new CreditWallet goes out
 *       with attempt + 1, or after the 3rd attempt the transfer moves to COMPENSATING and sends ReverseDebit;</li>
 *   <li>COMPENSATING (rule 7): the original ReverseDebit is republished; the reversal is never abandoned.</li>
 * </ul>
 * Each transfer is handled in its own transaction with its row locked, and checked again inside it, so a result
 * that arrived meanwhile wins and the sweeper does nothing.
 */
public class SweepTimeoutsUseCase {

    private static final System.Logger LOG = System.getLogger(SweepTimeoutsUseCase.class.getName());

    private final TransferRepository transfers;
    private final OutboxRepository outbox;
    private final SagaOutbox saga;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final SagaTimeouts timeouts;
    private final SagaTelemetry telemetry;

    public SweepTimeoutsUseCase(TransferRepository transfers, OutboxRepository outbox, SagaOutbox saga,
                                TransactionRunner transactions, IdGenerator ids, Clock clock, SagaTimeouts timeouts,
                                SagaTelemetry telemetry) {
        this.transfers = transfers;
        this.outbox = outbox;
        this.saga = saga;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.timeouts = timeouts;
        this.telemetry = telemetry;
    }

    /** Handles up to batchSize due transfers and returns how many it changed. */
    public int sweep(int batchSize) {
        Contract.require(batchSize > 0, "batchSize must be > 0: " + batchSize);
        Instant now = SagaClock.now(clock);
        List<UUID> due = transfers.findDue(now.minus(timeouts.pending()), now.minus(timeouts.credit()),
                now.minus(timeouts.reverseResend()), batchSize);
        int changed = 0;
        for (UUID transferId : due) {
            try {
                if (Boolean.TRUE.equals(transactions.inTransaction(() -> handle(transferId)))) {
                    changed++;
                }
            } catch (RuntimeException e) {
                // One transfer that fails (for example a lost outbox row) must not stop the others.
                LOG.log(System.Logger.Level.ERROR, () -> "Sweeper could not handle the transfer, retrying on the"
                        + " next run: " + e.getMessage() + " transferId=" + transferId, e);
            }
        }
        return changed;
    }

    private boolean handle(UUID transferId) {
        Instant now = SagaClock.now(clock);
        Transfer transfer = transfers.lockById(transferId).orElse(null);
        if (transfer == null || !isDue(transfer, now)) {
            return false;
        }
        return telemetry.transition(trigger(transfer.status()), transition -> {
            Transfer next = switch (transfer.status()) {
                case PENDING, COMPENSATING -> republish(transfer, now);
                case DEBITED -> creditTimedOut(transfer, now);
                default -> throw new IllegalStateException("a " + transfer.status() + " transfer is never due");
            };
            transfers.update(next);
            transition.transitioned(transfer, next);
            return true;
        });
    }

    // Only PENDING, DEBITED and COMPENSATING are ever due; any other status fails inside the transition.
    private static SagaTrigger trigger(TransferStatus status) {
        return switch (status) {
            case PENDING -> SagaTrigger.PENDING_TIMEOUT;
            case COMPENSATING -> SagaTrigger.REVERSE_RESEND;
            default -> SagaTrigger.CREDIT_TIMEOUT;
        };
    }

    // Checked again under the lock: a Wallet result may have moved the transfer since findDue.
    private boolean isDue(Transfer transfer, Instant now) {
        Instant sent = transfer.lastCommandAt();
        return switch (transfer.status()) {
            case PENDING -> !sent.isAfter(now.minus(timeouts.pending()));
            case DEBITED -> !sent.isAfter(now.minus(timeouts.credit()));
            case COMPENSATING -> !sent.isAfter(now.minus(timeouts.reverseResend()));
            default -> false;
        };
    }

    private Transfer republish(Transfer transfer, Instant now) {
        outbox.republish(transfer.lastCommandId());
        String command = transfer.status() == TransferStatus.PENDING ? "DebitWallet" : "ReverseDebit";
        log(transfer, "No reply in time: the original " + command + " is republished");
        return transfer.commandResent(now);
    }

    private Transfer creditTimedOut(Transfer transfer, Instant now) {
        saga.audit(transfer, AttemptStage.CREDIT, AttemptOutcome.FAILED, FailureReason.CREDIT_TIMEOUT.name(),
                transfer.recipientUserId(), null, now);
        Transfer next = transfer.canRetryCredit()
                ? transfer.creditRetried(ids.newId(), now)
                : transfer.compensating(FailureReason.CREDIT_TIMEOUT, ids.newId(), now);
        saga.sendCommand(next, now);
        log(transfer, next.status() == TransferStatus.DEBITED
                ? "CreditWallet attempt " + transfer.creditAttempts() + " timed out: attempt "
                + next.creditAttempts() + " sent"
                : "CreditWallet timed out " + Transfer.MAX_CREDIT_ATTEMPTS + " times: COMPENSATING, ReverseDebit sent");
        return next;
    }

    private static void log(Transfer transfer, String message) {
        LOG.log(System.Logger.Level.WARNING, () -> message
                + " correlationId=" + transfer.correlationId()
                + " transferId=" + transfer.transferId());
    }
}

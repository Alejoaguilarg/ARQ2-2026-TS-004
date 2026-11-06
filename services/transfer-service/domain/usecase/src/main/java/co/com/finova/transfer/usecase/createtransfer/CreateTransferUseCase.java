package co.com.finova.transfer.usecase.createtransfer;

import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.common.gateways.TransactionRunner;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.transfer.DuplicateIdempotencyKeyException;
import co.com.finova.transfer.model.transfer.IdempotencyKeyReusedException;
import co.com.finova.transfer.model.transfer.SagaTrigger;
import co.com.finova.transfer.model.transfer.SelfTransferNotAllowedException;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.model.transfer.gateways.SagaTelemetry;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import co.com.finova.transfer.usecase.saga.SagaClock;
import co.com.finova.transfer.usecase.saga.SagaOutbox;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * POST /transfers (spec §3.8, §4.6 rule 1): in one transaction the transfer is stored as PENDING and DebitWallet
 * and AttemptRecorded(ACCEPTED) are written to the outbox. A key the sender already used (§3.1.1 defense in
 * depth) answers the existing transfer when the body is the same, and fails with 422 when it is not. Every
 * rejected or duplicate request is audited too (§4.8).
 */
public class CreateTransferUseCase {

    static final int ACCEPTED = 202;
    static final int BAD_REQUEST = 400;
    static final int UNPROCESSABLE = 422;

    private static final System.Logger LOG = System.getLogger(CreateTransferUseCase.class.getName());

    private final TransferRepository transfers;
    private final SagaOutbox saga;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final SagaTelemetry telemetry;

    public CreateTransferUseCase(TransferRepository transfers, SagaOutbox saga, TransactionRunner transactions,
                                 IdGenerator ids, Clock clock, SagaTelemetry telemetry) {
        this.transfers = transfers;
        this.saga = saga;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    /** The created transfer, or the existing one with replayed = true. */
    public record Result(Transfer transfer, boolean replayed) {
    }

    private enum Rejection { SELF_TRANSFER, KEY_REUSED }

    // What one transaction decided: a transfer to answer, or a rejection already audited.
    private record Decision(Result result, Rejection rejection) {
    }

    public Result handle(TransferRequest request, UUID correlationId) {
        Decision decision;
        try {
            decision = transactions.inTransaction(() -> decide(request, correlationId));
        } catch (DuplicateIdempotencyKeyException e) {
            // A concurrent request with the same key committed first; this one answers with that transfer.
            decision = transactions.inTransaction(() -> decide(request, correlationId));
        }
        if (decision.rejection() == Rejection.SELF_TRANSFER) {
            throw new SelfTransferNotAllowedException();
        }
        if (decision.rejection() == Rejection.KEY_REUSED) {
            throw new IdempotencyKeyReusedException();
        }
        return decision.result();
    }

    /**
     * Audits a request rejected before it became a TransferRequest (spec §4.8: REQUEST / REJECTED with
     * VALIDATION_ERROR). The key is the raw header value, which may not be a UUID.
     */
    public void rejectInvalid(UUID senderUserId, String idempotencyKey, UUID correlationId) {
        transactions.inTransaction(() -> {
            saga.auditRejectedRequest("VALIDATION_ERROR", senderUserId, idempotencyKey, BAD_REQUEST, correlationId,
                    SagaClock.now(clock));
            return null;
        });
    }

    private Decision decide(TransferRequest request, UUID correlationId) {
        Instant now = SagaClock.now(clock);
        String key = request.idempotencyKey().toString();
        Optional<Transfer> existing = transfers.findByIdempotencyKey(request.senderUserId(), request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), request, correlationId, now);
        }
        if (request.senderUserId().equals(request.recipientUserId())) {
            saga.auditRejectedRequest("SELF_TRANSFER_NOT_ALLOWED", request.senderUserId(), key, BAD_REQUEST,
                    correlationId, now);
            return new Decision(null, Rejection.SELF_TRANSFER);
        }
        return telemetry.transition(SagaTrigger.CREATE_TRANSFER, transition -> {
            Transfer transfer = Transfer.create(request, ids.newId(), correlationId, ids.newId(), now);
            transfers.insert(transfer);
            saga.sendCommand(transfer, now);
            saga.audit(transfer, AttemptStage.REQUEST, AttemptOutcome.ACCEPTED, null, transfer.senderUserId(),
                    ACCEPTED, now);
            transition.transitioned(null, transfer);
            LOG.log(System.Logger.Level.INFO, () -> "Transfer accepted as PENDING, DebitWallet in the outbox"
                    + " correlationId=" + correlationId + " transferId=" + transfer.transferId());
            return new Decision(new Result(transfer, false), null);
        });
    }

    private Decision replay(Transfer existing, TransferRequest request, UUID correlationId, Instant now) {
        String key = request.idempotencyKey().toString();
        if (!existing.requestHash().equals(request.requestHash())) {
            saga.auditRejectedRequest("IDEMPOTENCY_KEY_REUSED", request.senderUserId(), key, UNPROCESSABLE,
                    correlationId, now);
            LOG.log(System.Logger.Level.WARNING, () -> "Idempotency-Key reused with another body"
                    + " correlationId=" + correlationId + " transferId=" + existing.transferId());
            return new Decision(null, Rejection.KEY_REUSED);
        }
        saga.audit(existing, AttemptStage.REQUEST, AttemptOutcome.DUPLICATE, "DUPLICATE_TRANSFER",
                existing.senderUserId(), ACCEPTED, now);
        LOG.log(System.Logger.Level.INFO, () -> "Existing transfer replayed for a repeated Idempotency-Key"
                + " correlationId=" + correlationId + " transferId=" + existing.transferId());
        return new Decision(new Result(existing, true), null);
    }
}

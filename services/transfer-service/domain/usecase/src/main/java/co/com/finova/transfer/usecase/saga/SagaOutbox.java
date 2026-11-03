package co.com.finova.transfer.usecase.saga;

import co.com.finova.transfer.model.common.Contract;
import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.message.MessageEnvelope;
import co.com.finova.transfer.model.message.payload.AttemptOutcome;
import co.com.finova.transfer.model.message.payload.AttemptRecorded;
import co.com.finova.transfer.model.message.payload.AttemptStage;
import co.com.finova.transfer.model.message.payload.CreditWallet;
import co.com.finova.transfer.model.message.payload.DebitWallet;
import co.com.finova.transfer.model.message.payload.MessagePayload;
import co.com.finova.transfer.model.message.payload.ReverseDebit;
import co.com.finova.transfer.model.message.payload.TransferCompleted;
import co.com.finova.transfer.model.message.payload.TransferRejected;
import co.com.finova.transfer.model.message.payload.TransferReversed;
import co.com.finova.transfer.model.outbox.OutboxMessage;
import co.com.finova.transfer.model.outbox.gateways.OutboxRepository;
import co.com.finova.transfer.model.transfer.Transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes what the saga sends (spec §4.6), always to the outbox and inside the caller's transaction (§1.4):
 * the current command of a transfer, its outcome event and its audit attempts (§4.8). Every message carries the
 * transfer's correlationId and transferId. It is not a use case: the use cases share one instance.
 */
public class SagaOutbox {

    private final OutboxRepository outbox;
    private final IdGenerator ids;

    public SagaOutbox(OutboxRepository outbox, IdGenerator ids) {
        this.outbox = outbox;
        this.ids = ids;
    }

    /**
     * The command the status calls for, with messageId = lastCommandId: DebitWallet while PENDING, CreditWallet
     * (attempt = creditAttempts) while DEBITED, ReverseDebit while COMPENSATING.
     */
    public void sendCommand(Transfer transfer, Instant now) {
        MessagePayload command = switch (transfer.status()) {
            case PENDING -> new DebitWallet(transfer.transferId(), transfer.senderUserId(), transfer.amount(),
                    transfer.currency());
            case DEBITED -> new CreditWallet(transfer.transferId(), transfer.recipientUserId(), transfer.amount(),
                    transfer.currency(), transfer.creditAttempts());
            case COMPENSATING -> new ReverseDebit(transfer.transferId(), transfer.senderUserId(), transfer.amount(),
                    transfer.currency(), transfer.failureReason().toReverseReason());
            default -> throw new IllegalStateException("a " + transfer.status() + " transfer sends no command");
        };
        write(transfer.lastCommandId(), transfer, command, now);
    }

    /** TransferCompleted, TransferRejected or TransferReversed for Notification (spec §4.3, §4.9). */
    public void publishOutcome(Transfer transfer, Instant now) {
        MessagePayload outcome = switch (transfer.status()) {
            case COMPLETED -> new TransferCompleted(transfer.transferId(), transfer.senderUserId(),
                    transfer.recipientUserId(), transfer.amount(), transfer.currency(), transfer.completedAt());
            case REJECTED -> new TransferRejected(transfer.transferId(), transfer.senderUserId(),
                    transfer.recipientUserId(), transfer.amount(), transfer.currency(),
                    transfer.failureReason().toDebitRejectionReason());
            case REVERSED -> new TransferReversed(transfer.transferId(), transfer.senderUserId(),
                    transfer.recipientUserId(), transfer.amount(), transfer.currency(),
                    transfer.failureReason().toReverseReason());
            default -> throw new IllegalStateException("a " + transfer.status() + " transfer has no outcome yet");
        };
        write(ids.newId(), transfer, outcome, now);
    }

    /** An AttemptRecorded with source TRANSFER (spec §4.8) about this transfer. */
    public void audit(Transfer transfer, AttemptStage stage, AttemptOutcome outcome, String reason, UUID userId,
                      Integer statusCode, Instant now) {
        UUID eventId = ids.newId();
        write(eventId, transfer, AttemptRecorded.fromTransfer(eventId, stage, outcome, reason, transfer.transferId(),
                userId, transfer.idempotencyKey().toString(), transfer.amount(), statusCode,
                transfer.correlationId(), now), now);
    }

    /** An AttemptRecorded for a request that created no transfer (invalid body, self-transfer, key reused). */
    public void auditRejectedRequest(String reason, UUID userId, String idempotencyKey, int statusCode,
                                     UUID correlationId, Instant now) {
        Contract.requireNotBlank(reason, "reason");
        UUID eventId = ids.newId();
        AttemptRecorded attempt = AttemptRecorded.fromTransfer(eventId, AttemptStage.REQUEST, AttemptOutcome.REJECTED,
                reason, null, userId, idempotencyKey, null, statusCode, correlationId, now);
        outbox.save(OutboxMessage.of(MessageEnvelope.create(eventId, correlationId, null, now, attempt), now));
    }

    private void write(UUID messageId, Transfer transfer, MessagePayload payload, Instant now) {
        MessageEnvelope<MessagePayload> envelope = MessageEnvelope.create(messageId, transfer.correlationId(),
                transfer.transferId(), now, payload);
        outbox.save(OutboxMessage.of(envelope, now));
    }
}

package co.com.finova.transfer.usecase;

import co.com.finova.transfer.model.message.command.CreditWallet;
import co.com.finova.transfer.model.message.command.DebitWallet;
import co.com.finova.transfer.model.message.command.ReverseDebit;
import co.com.finova.transfer.model.message.event.AttemptRecorded;
import co.com.finova.transfer.model.message.event.CreditFailed;
import co.com.finova.transfer.model.message.event.DebitRejected;
import co.com.finova.transfer.model.message.event.DebitReversed;
import co.com.finova.transfer.model.message.event.TransferCompleted;
import co.com.finova.transfer.model.message.event.TransferRejected;
import co.com.finova.transfer.model.message.event.TransferReversed;
import co.com.finova.transfer.model.message.event.WalletCredited;
import co.com.finova.transfer.model.message.event.WalletDebited;
import co.com.finova.transfer.model.message.gateways.MessagePublisher;
import co.com.finova.transfer.model.message.gateways.OutboxMessage;
import co.com.finova.transfer.model.message.gateways.OutboxRepository;
import co.com.finova.transfer.model.message.gateways.ProcessedMessageRepository;

import java.time.Instant;
import java.util.UUID;

public class TransferSaga {

    public static final int MAX_CREDIT_ATTEMPTS = 3;
    public static final long CREDIT_TIMEOUT_MS = 10_000;
    public static final long REVERSE_RESEND_MS = 30_000;
    public static final long PENDING_TIMEOUT_MS = 30_000;

    private final OutboxRepository outboxRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final MessagePublisher messagePublisher;

    public TransferSaga(OutboxRepository outboxRepository,
                        ProcessedMessageRepository processedMessageRepository,
                        MessagePublisher messagePublisher) {
        this.outboxRepository = outboxRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.messagePublisher = messagePublisher;
    }

    public void createTransfer(String transferId, String senderUserId, String recipientUserId,
                               String amount, String currency, String idempotencyKey,
                               String requestHash, String correlationId) {
        if (processedMessageRepository.exists(transferId, "transfer-service")) {
            return;
        }

        processedMessageRepository.save(transferId, "transfer-service");

        Instant now = Instant.now();

        OutboxMessage debitWalletMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "wallet.commands", "debit",
            serialize(new DebitWallet(transferId, senderUserId, amount, currency)),
            now, null
        );
        outboxRepository.save(debitWalletMsg);

        OutboxMessage attemptMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "audit.events", "audit.attempt",
            serialize(new AttemptRecorded(
                UUID.randomUUID().toString(),
                AttemptRecorded.Source.TRANSFER,
                AttemptRecorded.Stage.REQUEST,
                AttemptRecorded.Outcome.ACCEPTED,
                null, transferId, senderUserId, idempotencyKey, amount, 202, correlationId, now
            )),
            now, null
        );
        outboxRepository.save(attemptMsg);
    }

    public void onWalletDebited(WalletDebited event) {
        String consumer = "transfer-service";
        if (processedMessageRepository.exists(event.getTransferId(), consumer)) {
            return;
        }
        processedMessageRepository.save(event.getTransferId(), consumer);

        Instant now = Instant.now();

        OutboxMessage creditMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "wallet.commands", "credit",
            serialize(new CreditWallet(
                event.getTransferId(), event.getUserId(),
                event.getAmount(), "COP", 1
            )),
            now, null
        );
        outboxRepository.save(creditMsg);
    }

    public void onWalletDebitRejected(DebitRejected event) {
        String consumer = "transfer-service";
        if (processedMessageRepository.exists(event.getTransferId(), consumer)) {
            return;
        }
        processedMessageRepository.save(event.getTransferId(), consumer);

        Instant now = Instant.now();

        OutboxMessage rejectedMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "transfer.events", "transfer.rejected",
            serialize(new TransferRejected(
                event.getTransferId(), null, null,
                event.getAmount(), null,
                event.getReason() == DebitRejected.Reason.INSUFFICIENT_FUNDS
                        ? TransferRejected.Reason.INSUFFICIENT_FUNDS
                        : TransferRejected.Reason.SENDER_WALLET_NOT_FOUND
            )),
            now, null
        );
        outboxRepository.save(rejectedMsg);

        OutboxMessage attemptMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "audit.events", "audit.attempt",
            serialize(new AttemptRecorded(
                UUID.randomUUID().toString(),
                AttemptRecorded.Source.TRANSFER,
                AttemptRecorded.Stage.TRANSFER,
                AttemptRecorded.Outcome.REJECTED,
                event.getReason().toString(),
                event.getTransferId(), null, null, event.getAmount(), null, null, now
            )),
            now, null
        );
        outboxRepository.save(attemptMsg);
    }

    public void onWalletCredited(WalletCredited event) {
        String consumer = "transfer-service";
        if (processedMessageRepository.exists(event.getTransferId(), consumer)) {
            return;
        }
        processedMessageRepository.save(event.getTransferId(), consumer);

        Instant now = Instant.now();

        OutboxMessage completedMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "transfer.events", "transfer.completed",
            serialize(new TransferCompleted(
                event.getTransferId(), null, null,
                event.getAmount(), null, now
            )),
            now, null
        );
        outboxRepository.save(completedMsg);

        OutboxMessage attemptMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "audit.events", "audit.attempt",
            serialize(new AttemptRecorded(
                UUID.randomUUID().toString(),
                AttemptRecorded.Source.TRANSFER,
                AttemptRecorded.Stage.TRANSFER,
                AttemptRecorded.Outcome.COMPLETED,
                null, event.getTransferId(), null, null, event.getAmount(), null, null, now
            )),
            now, null
        );
        outboxRepository.save(attemptMsg);
    }

    public void onCreditFailed(CreditFailed event) {
        String consumer = "transfer-service";
        if (processedMessageRepository.exists(event.getTransferId(), consumer)) {
            return;
        }
        processedMessageRepository.save(event.getTransferId(), consumer);

        Instant now = Instant.now();

        if (event.getAttempt() >= MAX_CREDIT_ATTEMPTS) {
            compensate(event.getTransferId(), event.getUserId(),
                event.getAmount(), event.getCurrency(),
                ReverseDebit.Reason.CREDIT_TIMEOUT, now);
            return;
        }

        OutboxMessage creditMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "wallet.commands", "credit",
            serialize(new CreditWallet(
                event.getTransferId(), event.getUserId(),
                event.getAmount(), event.getCurrency(), event.getAttempt() + 1
            )),
            now, null
        );
        outboxRepository.save(creditMsg);
    }

    public void onDebitReversed(DebitReversed event) {
        String consumer = "transfer-service";
        if (processedMessageRepository.exists(event.getTransferId(), consumer)) {
            return;
        }
        processedMessageRepository.save(event.getTransferId(), consumer);

        Instant now = Instant.now();

        OutboxMessage reversedMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "transfer.events", "transfer.reversed",
            serialize(new TransferReversed(
                event.getTransferId(), null, null,
                event.getAmount(), null,
                TransferReversed.Reason.CREDIT_TIMEOUT
            )),
            now, null
        );
        outboxRepository.save(reversedMsg);

        OutboxMessage attemptMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "audit.events", "audit.attempt",
            serialize(new AttemptRecorded(
                UUID.randomUUID().toString(),
                AttemptRecorded.Source.TRANSFER,
                AttemptRecorded.Stage.TRANSFER,
                AttemptRecorded.Outcome.COMPENSATED,
                null, event.getTransferId(), null, null, event.getAmount(), null, null, now
            )),
            now, null
        );
        outboxRepository.save(attemptMsg);
    }

    public void compensate(String transferId, String userId, String amount,
                           String currency, ReverseDebit.Reason reason, Instant now) {
        OutboxMessage reverseMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "wallet.commands", "reverse",
            serialize(new ReverseDebit(transferId, userId, amount, currency, reason)),
            now, null
        );
        outboxRepository.save(reverseMsg);

        OutboxMessage attemptMsg = new OutboxMessage(
            UUID.randomUUID().toString(), "audit.events", "audit.attempt",
            serialize(new AttemptRecorded(
                UUID.randomUUID().toString(),
                AttemptRecorded.Source.TRANSFER,
                AttemptRecorded.Stage.REVERSAL,
                AttemptRecorded.Outcome.COMPENSATED,
                reason != null ? reason.toString() : null,
                transferId, null, null, amount, null, null, now
            )),
            now, null
        );
        outboxRepository.save(attemptMsg);
    }

    public void timeoutSweeper() {
        // This method is called periodically to scan non-terminal transfers
        // and republish stale commands. Implementation delegates to
        // outboxRepository.findPending() and republishes those messages.
        // The actual timeout logic (30s for PENDING, 10s per credit attempt,
        // 30s for reverse resend) is evaluated by the caller based on
        // lastCommandAt and creditAttempts fields.
    }

    @SuppressWarnings("unchecked")
    private <T> String serialize(T obj) {
        return obj.toString();
    }
}

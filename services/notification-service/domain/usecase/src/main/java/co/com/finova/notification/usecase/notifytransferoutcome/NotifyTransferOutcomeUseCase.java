package co.com.finova.notification.usecase.notifytransferoutcome;

import co.com.finova.notification.model.exceptions.NotificationDeliveryException;
import co.com.finova.notification.model.exceptions.PendingNotificationsException;
import co.com.finova.notification.model.exceptions.RecipientUnreachableException;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.NotificationLog;
import co.com.finova.notification.model.notification.NotificationMessages;
import co.com.finova.notification.model.notification.NotificationStatus;
import co.com.finova.notification.model.notification.RecipientContact;
import co.com.finova.notification.model.notification.TransferOutcome;
import co.com.finova.notification.model.notification.gateways.NotificationLogRepository;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;
import co.com.finova.notification.model.notification.gateways.RecipientContactRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Notifies the users of a transfer outcome (spec §4.9).
 *
 * <p>For each user: skip it if its notification is already SENT, resolve its contact in
 * recipient_contacts, send through its preferred channel and record the result in
 * notification_log (one row per transfer, event type and user).</p>
 *
 * <p>The notification is sent first and recorded afterwards, without keeping a database
 * transaction open while the provider answers. If the service stops between both steps, the
 * message is redelivered and that user may receive the notification twice (at-least-once).</p>
 */
public class NotifyTransferOutcomeUseCase {

    private final RecipientContactRepository contactRepository;
    private final NotificationLogRepository logRepository;
    private final NotificationProvider notificationProvider;
    private final Clock clock;

    public NotifyTransferOutcomeUseCase(RecipientContactRepository contactRepository,
                                        NotificationLogRepository logRepository,
                                        NotificationProvider notificationProvider,
                                        Clock clock) {
        this.contactRepository = contactRepository;
        this.logRepository = logRepository;
        this.notificationProvider = notificationProvider;
        this.clock = clock;
    }

    /**
     * @throws PendingNotificationsException  if the provider did not accept at least one
     *                                        notification; the message must be retried
     * @throws RecipientUnreachableException  if a user has no usable contact data and every other
     *                                        notification was delivered; retrying will not help
     */
    public void execute(TransferOutcome outcome) {
        List<UUID> pending = new ArrayList<>();
        List<UUID> unreachable = new ArrayList<>();

        for (UUID userId : outcome.usersToNotify()) {
            try {
                notifyUser(outcome, userId);
            } catch (NotificationDeliveryException e) {
                pending.add(userId);
            } catch (RecipientUnreachableException e) {
                unreachable.add(userId);
            }
        }

        if (!pending.isEmpty()) {
            throw new PendingNotificationsException(outcome.transferId(), pending);
        }
        if (!unreachable.isEmpty()) {
            throw new RecipientUnreachableException(outcome.transferId(), unreachable);
        }
    }

    private void notifyUser(TransferOutcome outcome, UUID userId) {
        Optional<NotificationLog> previous = logRepository.find(outcome.transferId(), outcome.type(), userId);
        if (previous.isPresent() && previous.get().isSent()) {
            return; // a SENT notification is never sent again
        }

        RecipientContact contact = contactRepository.findByUserId(userId)
                .orElseThrow(() -> new RecipientUnreachableException(outcome.transferId(), List.of(userId)));
        Channel channel = contact.preferredChannel();
        String address = contact.addressFor(channel)
                .orElseThrow(() -> new RecipientUnreachableException(outcome.transferId(), List.of(userId)));

        UUID notificationId = previous.map(NotificationLog::notificationId).orElseGet(UUID::randomUUID);
        Notification notification = new Notification(
                notificationId,
                channel,
                address,
                outcome.type(),
                outcome.transferId(),
                outcome.amount(),
                outcome.currency(),
                outcome.reason(),
                NotificationMessages.subject(outcome, userId),
                NotificationMessages.body(outcome, userId),
                outcome.correlationId());

        try {
            notificationProvider.send(notification);
        } catch (NotificationDeliveryException e) {
            record(previous, outcome, userId, notificationId, channel, NotificationStatus.FAILED);
            throw e;
        }
        record(previous, outcome, userId, notificationId, channel, NotificationStatus.SENT);
    }

    private void record(Optional<NotificationLog> previous, TransferOutcome outcome, UUID userId,
                        UUID notificationId, Channel channel, NotificationStatus status) {
        Instant now = clock.instant();
        NotificationLog row = previous
                .map(log -> log.nextAttempt(channel, status, now))
                .orElseGet(() -> NotificationLog.firstAttempt(notificationId, outcome.transferId(),
                        outcome.type(), userId, channel, status, now));
        logRepository.save(row);
    }
}

package co.com.finova.notification.usecase.notifytransferoutcome;

import co.com.finova.notification.model.exceptions.PendingNotificationsException;
import co.com.finova.notification.model.exceptions.RecipientUnreachableException;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.NotificationLog;
import co.com.finova.notification.model.notification.NotificationStatus;
import co.com.finova.notification.model.notification.RecipientContact;
import co.com.finova.notification.model.notification.TransferEventType;
import co.com.finova.notification.model.notification.TransferOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static co.com.finova.notification.model.notification.TransferEventType.TRANSFER_COMPLETED;
import static co.com.finova.notification.model.notification.TransferEventType.TRANSFER_REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class NotifyTransferOutcomeUseCaseTest {

    private static final UUID SENDER = UUID.fromString("f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b");
    private static final UUID RECIPIENT = UUID.fromString("b3c9a1e2-5d4f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final UUID TRANSFER = UUID.fromString("a1b2c3d4-e5f6-4890-abcd-ef1234567890");
    private static final String SENDER_EMAIL = "usuario.a@finova.test";
    private static final String RECIPIENT_PHONE = "+573000000002";

    private InMemoryAdapters.Contacts contacts;
    private InMemoryAdapters.Logs logs;
    private InMemoryAdapters.Provider provider;
    private NotifyTransferOutcomeUseCase useCase;

    @BeforeEach
    void setUp() {
        contacts = new InMemoryAdapters.Contacts()
                .add(new RecipientContact(SENDER, SENDER_EMAIL, "+573000000001", null, Channel.EMAIL))
                .add(new RecipientContact(RECIPIENT, "usuario.b@finova.test", RECIPIENT_PHONE, null, Channel.SMS));
        logs = new InMemoryAdapters.Logs();
        provider = new InMemoryAdapters.Provider();
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneOffset.UTC);
        useCase = new NotifyTransferOutcomeUseCase(contacts, logs, provider, clock);
    }

    @Test
    void completedTransferNotifiesBothUsersThroughTheirPreferredChannel() {
        useCase.execute(outcome(TRANSFER_COMPLETED, null));

        assertThat(provider.sent())
                .extracting(Notification::channel, Notification::to)
                .containsExactly(
                        tuple(Channel.EMAIL, SENDER_EMAIL),
                        tuple(Channel.SMS, RECIPIENT_PHONE));
        assertSent(TRANSFER_COMPLETED, SENDER, 1);
        assertSent(TRANSFER_COMPLETED, RECIPIENT, 1);
    }

    @Test
    void rejectedTransferOnlyNotifiesTheSender() {
        useCase.execute(outcome(TRANSFER_REJECTED, "INSUFFICIENT_FUNDS"));

        assertThat(provider.sent()).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.to()).isEqualTo(SENDER_EMAIL);
                    assertThat(notification.message()).contains("fondos insuficientes");
                });
        assertThat(logs.size()).isEqualTo(1);
    }

    @Test
    void whenOneDeliveryFailsTheOtherIsStillSentAndTheMessageMustBeRetried() {
        provider.failFor(RECIPIENT_PHONE);

        assertThatThrownBy(() -> useCase.execute(outcome(TRANSFER_COMPLETED, null)))
                .isInstanceOf(PendingNotificationsException.class)
                .satisfies(e -> assertThat(((PendingNotificationsException) e).getUserIds())
                        .containsExactly(RECIPIENT));

        assertSent(TRANSFER_COMPLETED, SENDER, 1);
        NotificationLog failed = logs.of(TRANSFER, TRANSFER_COMPLETED, RECIPIENT);
        assertThat(failed.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
    }

    @Test
    void retryOnlySendsWhatIsPendingAndUpdatesTheSameRow() {
        provider.failFor(RECIPIENT_PHONE);
        assertThatThrownBy(() -> useCase.execute(outcome(TRANSFER_COMPLETED, null)))
                .isInstanceOf(PendingNotificationsException.class);
        UUID firstNotificationId = logs.of(TRANSFER, TRANSFER_COMPLETED, RECIPIENT).notificationId();

        provider.recover(RECIPIENT_PHONE);
        useCase.execute(outcome(TRANSFER_COMPLETED, null));

        assertThat(provider.sent()).extracting(Notification::to)
                .containsExactly(SENDER_EMAIL, RECIPIENT_PHONE); // the sender got exactly one notification
        NotificationLog retried = logs.of(TRANSFER, TRANSFER_COMPLETED, RECIPIENT);
        assertThat(retried.notificationId()).isEqualTo(firstNotificationId);
        assertThat(retried.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(logs.size()).isEqualTo(2);
    }

    @Test
    void aRedeliveredMessageIsNotSentAgain() {
        useCase.execute(outcome(TRANSFER_COMPLETED, null));
        useCase.execute(outcome(TRANSFER_COMPLETED, null));

        assertThat(provider.sent()).hasSize(2);
        assertSent(TRANSFER_COMPLETED, SENDER, 1);
        assertSent(TRANSFER_COMPLETED, RECIPIENT, 1);
    }

    @Test
    void userWithoutContactIsUnreachableButTheOthersAreNotified() {
        UUID unknownRecipient = UUID.randomUUID();
        TransferOutcome outcome = new TransferOutcome(TRANSFER_COMPLETED, TRANSFER, SENDER, unknownRecipient,
                "150000.00", "COP", null, null);

        assertThatThrownBy(() -> useCase.execute(outcome))
                .isInstanceOf(RecipientUnreachableException.class)
                .satisfies(e -> assertThat(((RecipientUnreachableException) e).getUserIds())
                        .containsExactly(unknownRecipient));

        assertThat(provider.sent()).extracting(Notification::to).containsExactly(SENDER_EMAIL);
    }

    @Test
    void contactWithoutAddressForItsPreferredChannelIsUnreachable() {
        contacts.add(new RecipientContact(SENDER, SENDER_EMAIL, null, null, Channel.PUSH));

        assertThatThrownBy(() -> useCase.execute(outcome(TRANSFER_REJECTED, "INSUFFICIENT_FUNDS")))
                .isInstanceOf(RecipientUnreachableException.class);
        assertThat(provider.sent()).isEmpty();
        assertThat(logs.size()).isZero();
    }

    @Test
    void aDeliveryFailureWinsOverAnUnreachableUserSoTheMessageIsRetried() {
        provider.failFor(SENDER_EMAIL);
        UUID unknownRecipient = UUID.randomUUID();
        TransferOutcome outcome = new TransferOutcome(TRANSFER_COMPLETED, TRANSFER, SENDER, unknownRecipient,
                "150000.00", "COP", null, null);

        assertThatThrownBy(() -> useCase.execute(outcome)).isInstanceOf(PendingNotificationsException.class);
    }

    private void assertSent(TransferEventType type, UUID userId, int attempts) {
        NotificationLog row = logs.of(TRANSFER, type, userId);
        assertThat(row.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.attempts()).isEqualTo(attempts);
    }

    private static TransferOutcome outcome(TransferEventType type, String reason) {
        return new TransferOutcome(type, TRANSFER, SENDER, RECIPIENT, "150000.00", "COP", reason, "corr-1");
    }
}

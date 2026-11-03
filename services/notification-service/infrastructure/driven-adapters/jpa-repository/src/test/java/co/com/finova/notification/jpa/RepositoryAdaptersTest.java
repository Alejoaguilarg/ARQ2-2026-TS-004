package co.com.finova.notification.jpa;

import co.com.finova.notification.jpa.entity.NotificationLogEntity;
import co.com.finova.notification.jpa.entity.RecipientContactEntity;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.NotificationLog;
import co.com.finova.notification.model.notification.NotificationStatus;
import co.com.finova.notification.model.notification.TransferEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mapping between the domain records and the JPA entities, without a database
 * (JpaAdaptersIntegrationTest covers the real PostgreSQL).
 */
class RepositoryAdaptersTest {

    private static final UUID USER = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-02T15:00:00Z");

    private final RecipientContactJpaRepository contactJpa = mock(RecipientContactJpaRepository.class);
    private final NotificationLogJpaRepository logJpa = mock(NotificationLogJpaRepository.class);

    @Test
    void mapsAContactToTheDomain() {
        RecipientContactEntity entity = new RecipientContactEntity();
        entity.setUserId(USER);
        entity.setEmail("ana@example.com");
        entity.setPhone("+573001112233");
        entity.setPreferredChannel(Channel.EMAIL);
        when(contactJpa.findById(USER)).thenReturn(Optional.of(entity));

        RecipientContactRepositoryAdapter adapter = new RecipientContactRepositoryAdapter(contactJpa);

        assertThat(adapter.findByUserId(USER)).hasValueSatisfying(contact -> {
            assertThat(contact.email()).isEqualTo("ana@example.com");
            assertThat(contact.addressFor(Channel.SMS)).contains("+573001112233");
            assertThat(contact.preferredChannel()).isEqualTo(Channel.EMAIL);
        });
        assertThat(adapter.findByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void storesTheEventTypeWithTheSpecNameAndReadsItBack() {
        NotificationLog log = NotificationLog.firstAttempt(UUID.randomUUID(), TRANSFER,
                TransferEventType.TRANSFER_REVERSED, USER, Channel.SMS, NotificationStatus.FAILED, T0);
        NotificationLogRepositoryAdapter adapter = new NotificationLogRepositoryAdapter(logJpa);

        adapter.save(log);

        ArgumentCaptor<NotificationLogEntity> saved = ArgumentCaptor.forClass(NotificationLogEntity.class);
        verify(logJpa).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("TransferReversed");

        when(logJpa.findByTransferIdAndEventTypeAndUserId(TRANSFER, "TransferReversed", USER))
                .thenReturn(Optional.of(saved.getValue()));
        assertThat(adapter.find(TRANSFER, TransferEventType.TRANSFER_REVERSED, USER)).contains(log);
    }

    @Test
    void anUnknownEventTypeInTheDatabaseIsAnError() {
        NotificationLogEntity entity = NotificationLogRepositoryAdapter.toEntity(NotificationLog.firstAttempt(
                UUID.randomUUID(), TRANSFER, TransferEventType.TRANSFER_COMPLETED, USER, Channel.EMAIL,
                NotificationStatus.SENT, T0));
        entity.setEventType("WalletDebited");

        assertThatIllegalStateException().isThrownBy(() -> NotificationLogRepositoryAdapter.toDomain(entity));
    }
}

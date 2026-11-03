package co.com.finova.notification.jpa;

import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.NotificationLog;
import co.com.finova.notification.model.notification.NotificationStatus;
import co.com.finova.notification.model.notification.TransferEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the Flyway migrations on a real PostgreSQL 16 and checks that the entities match the schema
 * (ddl-auto=validate). Skipped when Docker is not running.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({NotificationLogRepositoryAdapter.class, RecipientContactRepositoryAdapter.class})
class JpaAdaptersIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID USER_A = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final Instant T0 = Instant.parse("2026-11-02T15:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-02T15:00:05Z");

    @Autowired
    private NotificationLogRepositoryAdapter logs;

    @Autowired
    private RecipientContactRepositoryAdapter contacts;

    @Autowired
    private NotificationLogJpaRepository jpaRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void flywayLoadsTheInitialContacts() {
        assertThat(contacts.findByUserId(USER_A)).hasValueSatisfying(contact -> {
            assertThat(contact.email()).isEqualTo("ana@example.com");
            assertThat(contact.preferredChannel()).isEqualTo(Channel.EMAIL);
        });
        assertThat(contacts.findByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aRetryUpdatesTheSameRow() {
        UUID transferId = UUID.randomUUID();
        NotificationLog first = NotificationLog.firstAttempt(UUID.randomUUID(), transferId,
                TransferEventType.TRANSFER_COMPLETED, USER_A, Channel.EMAIL, NotificationStatus.FAILED, T0);
        logs.save(first);
        flushAndClear();

        NotificationLog stored = logs.find(transferId, TransferEventType.TRANSFER_COMPLETED, USER_A).orElseThrow();
        assertThat(stored).isEqualTo(first);

        logs.save(stored.nextAttempt(Channel.EMAIL, NotificationStatus.SENT, T1));
        flushAndClear();

        NotificationLog retried = logs.find(transferId, TransferEventType.TRANSFER_COMPLETED, USER_A).orElseThrow();
        assertThat(retried.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(retried.createdAt()).isEqualTo(T0);
        assertThat(retried.updatedAt()).isEqualTo(T1);
        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    @Test
    void thereIsOnlyOneRowPerTransferEventAndUser() {
        UUID transferId = UUID.randomUUID();
        jpaRepository.saveAndFlush(NotificationLogRepositoryAdapter.toEntity(NotificationLog.firstAttempt(
                UUID.randomUUID(), transferId, TransferEventType.TRANSFER_REJECTED, USER_A, Channel.EMAIL,
                NotificationStatus.SENT, T0)));

        NotificationLog duplicate = NotificationLog.firstAttempt(UUID.randomUUID(), transferId,
                TransferEventType.TRANSFER_REJECTED, USER_A, Channel.EMAIL, NotificationStatus.SENT, T1);

        assertThatThrownBy(() -> jpaRepository.saveAndFlush(NotificationLogRepositoryAdapter.toEntity(duplicate)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}

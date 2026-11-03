package co.com.finova.audit.jpa.record;

import co.com.finova.audit.jpa.AuditDatabase;
import co.com.finova.audit.jpa.transaction.SpringTransactionRunner;
import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import co.com.finova.audit.model.query.AuditPage;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The repository against a real audit_db, connected as the service is (SET ROLE audit_rw). */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAuditRecordRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = AuditDatabase.container();

    private static HikariDataSource dataSource;

    private JdbcAuditRecordRepository repository;
    private SpringTransactionRunner transactions;

    @BeforeAll
    static void migrate() {
        AuditDatabase.migrate(POSTGRES);
        dataSource = AuditDatabase.asService(POSTGRES);
    }

    @AfterAll
    static void close() {
        dataSource.close();
    }

    @BeforeEach
    void setUp() {
        repository = new JdbcAuditRecordRepository(JdbcClient.create(dataSource));
        transactions = new SpringTransactionRunner(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void aRecordReadBackIsEqualAndItsHashStillMatches() {
        AttemptRecorded attempt = new AttemptRecorded(UUID.randomUUID(), AttemptSource.GATEWAY,
                AttemptStage.REQUEST, AttemptOutcome.REJECTED, "quote \" and ñ", UUID.randomUUID(), null,
                "key-1", "000150000.00", 422, UUID.randomUUID(), Instant.parse("2026-11-02T10:15:30.123456789Z"));

        AuditRecord stored = append(attempt);
        List<AuditRecord> readBack = repository.findRange(stored.seq(), stored.seq(), 10);

        assertEquals(List.of(stored), readBack);
        assertTrue(readBack.get(0).isIntact());
        assertEquals("150000.00", readBack.get(0).attempt().amount());
    }

    @Test
    void consecutiveRecordsAreLinked() {
        AuditRecord first = append(attempt(UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
        AuditRecord second = append(attempt(UUID.randomUUID(), UUID.randomUUID(), Instant.now()));

        assertEquals(first.seq() + 1, second.seq());
        assertEquals(first.hash(), second.prevHash());
        assertEquals(second.link(), repository.findLastLink().orElseThrow());
        assertEquals(first.link(), repository.findLink(first.seq()).orElseThrow());
    }

    @Test
    void remembersEveryEventId() {
        AttemptRecorded attempt = attempt(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        append(attempt);

        assertTrue(repository.contains(attempt.eventId()));
        assertFalse(repository.contains(UUID.randomUUID()));
    }

    @Test
    void theDatabaseRejectsASecondRecordWithTheSameEventId() {
        AttemptRecorded attempt = attempt(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        append(attempt);

        assertThrows(DataAccessException.class, () -> transactions.inTransaction(() -> {
            ChainLink last = repository.findLastLink().orElseThrow();
            repository.append(AuditRecord.append(last, attempt, Instant.now()));
            return null;
        }));
    }

    @Test
    void findsTheRecordsOfATransferInSeqOrderWithTheTotal() {
        UUID transferId = UUID.randomUUID();
        AuditRecord a = append(attempt(UUID.randomUUID(), transferId, Instant.now()));
        append(attempt(UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
        AuditRecord b = append(attempt(UUID.randomUUID(), transferId, Instant.now()));
        AuditRecord c = append(attempt(UUID.randomUUID(), transferId, Instant.now()));

        AuditPage first = repository.find(AuditQuery.of(transferId, null, null, null, 0, 2));
        AuditPage second = repository.find(AuditQuery.of(transferId, null, null, null, 1, 2));

        assertEquals(3, first.total());
        assertEquals(List.of(a, b), first.items());
        assertEquals(List.of(c), second.items());
    }

    @Test
    void findsByUserAndOccurredAtRange() {
        UUID userId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-11-02T12:00:00Z");
        append(attempt(userId, UUID.randomUUID(), t0.minus(1, ChronoUnit.HOURS)));
        AuditRecord inRange = append(attempt(userId, UUID.randomUUID(), t0));
        append(attempt(userId, UUID.randomUUID(), t0.plus(1, ChronoUnit.HOURS)));

        AuditPage page = repository.find(AuditQuery.of(null, userId, t0.minusSeconds(60), t0.plusSeconds(60),
                null, null));

        assertEquals(List.of(inRange), page.items());
    }

    private AuditRecord append(AttemptRecorded attempt) {
        return transactions.inTransaction(() -> {
            repository.lockChain();
            AuditRecord record = AuditRecord.append(repository.findLastLink().orElse(ChainLink.GENESIS), attempt,
                    Instant.now());
            repository.append(record);
            return record;
        });
    }

    private static AttemptRecorded attempt(UUID userId, UUID transferId, Instant occurredAt) {
        return new AttemptRecorded(UUID.randomUUID(), AttemptSource.WALLET, AttemptStage.DEBIT,
                AttemptOutcome.APPLIED, null, transferId, userId, null, "150000.00", null, UUID.randomUUID(),
                occurredAt);
    }
}

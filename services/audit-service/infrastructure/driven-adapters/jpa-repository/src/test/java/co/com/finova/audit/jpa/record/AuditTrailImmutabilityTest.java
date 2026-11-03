package co.com.finova.audit.jpa.record;

import co.com.finova.audit.jpa.AuditDatabase;
import co.com.finova.audit.jpa.transaction.SpringTransactionRunner;
import co.com.finova.audit.model.attempt.AttemptOutcome;
import co.com.finova.audit.model.attempt.AttemptRecorded;
import co.com.finova.audit.model.attempt.AttemptSource;
import co.com.finova.audit.model.attempt.AttemptStage;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F5.4 and F5.5 against a real audit_db: nobody can change a record, and a forced change is detected. */
@Testcontainers(disabledWithoutDocker = true)
class AuditTrailImmutabilityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = AuditDatabase.container();

    private static HikariDataSource service;
    private static JdbcClient asService;
    private static JdbcClient asOwner;
    private static JdbcAuditRecordRepository repository;
    private static AuditRecord stored;

    @BeforeAll
    static void storeOneRecord() {
        AuditDatabase.migrate(POSTGRES);
        service = AuditDatabase.asService(POSTGRES);
        asService = JdbcClient.create(service);
        asOwner = JdbcClient.create(AuditDatabase.asOwner(POSTGRES));
        repository = new JdbcAuditRecordRepository(asService);
        AttemptRecorded attempt = new AttemptRecorded(UUID.randomUUID(), AttemptSource.WALLET, AttemptStage.DEBIT,
                AttemptOutcome.APPLIED, null, UUID.randomUUID(), UUID.randomUUID(), null, "150000.00", null,
                UUID.randomUUID(), Instant.now());
        stored = new SpringTransactionRunner(new DataSourceTransactionManager(service)).inTransaction(() -> {
            repository.lockChain();
            AuditRecord record = AuditRecord.append(ChainLink.GENESIS, attempt, Instant.now());
            repository.append(record);
            return record;
        });
    }

    @AfterAll
    static void close() {
        service.close();
    }

    @Test
    void theServiceRoleHasNoUpdateDeleteOrTruncatePrivilege() {
        assertDenied(asService, "UPDATE audit_records SET amount = 1.00", "permission denied");
        assertDenied(asService, "DELETE FROM audit_records", "permission denied");
        assertDenied(asService, "TRUNCATE audit_records", "permission denied");
        assertDenied(asService, "DELETE FROM audit_event_ids", "permission denied");
    }

    @Test
    void theTriggerStopsEvenTheOwner() {
        assertDenied(asOwner, "UPDATE audit_records SET amount = 1.00", "append-only");
        assertDenied(asOwner, "DELETE FROM audit_records", "append-only");
        assertDenied(asOwner, "TRUNCATE audit_records", "append-only");
        assertDenied(asOwner, "TRUNCATE audit_records_default", "append-only");
        assertDenied(asOwner, "UPDATE audit_event_ids SET seq = 99", "append-only");
    }

    @Test
    void aRowChangedBehindTheGuardsNoLongerMatchesItsHash() throws SQLException {
        assertTrue(repository.findRange(stored.seq(), stored.seq(), 1).get(0).isIntact());

        // A superuser can switch triggers off for its session: the hash chain is what catches it (ADR 005).
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.executeUpdate("UPDATE audit_records SET amount = 1.00 WHERE seq = " + stored.seq());
        }

        AuditRecord altered = repository.findRange(stored.seq(), stored.seq(), 1).get(0);
        assertEquals("1.00", altered.attempt().amount());
        assertFalse(altered.isIntact());
    }

    @Test
    void theServiceRoleCanCreateTheNextPartitionsThroughTheFunction() {
        String name = asService.sql("SELECT audit_ensure_month_partition(DATE '2031-03-15')")
                .query(String.class)
                .single();

        assertEquals("audit_records_y2031m03", name);
        assertDenied(asOwner, "TRUNCATE audit_records_y2031m03", "append-only");
    }

    private static void assertDenied(JdbcClient client, String sql, String reason) {
        DataAccessException e = assertThrows(DataAccessException.class, () -> client.sql(sql).update());
        assertTrue(String.valueOf(e.getMostSpecificCause().getMessage()).contains(reason),
                () -> sql + " failed for another reason: " + e.getMostSpecificCause().getMessage());
    }
}

package co.com.finova.audit.jpa.partition;

import co.com.finova.audit.jpa.AuditDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** ADR 005: the partitions of this month and the next one exist ahead of time, created as the service. */
@Testcontainers(disabledWithoutDocker = true)
class AuditPartitionMaintenanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = AuditDatabase.container();

    private static HikariDataSource service;

    @BeforeAll
    static void migrate() {
        AuditDatabase.migrate(POSTGRES);
        service = AuditDatabase.asService(POSTGRES);
    }

    @AfterAll
    static void close() {
        service.close();
    }

    @Test
    void createsThisMonthAndNextMonthAndIsSafeToRepeat() {
        Clock december = Clock.fixed(Instant.parse("2031-12-15T10:00:00Z"), ZoneOffset.UTC);
        var maintenance = new AuditPartitionMaintenance(JdbcClient.create(service), december);

        maintenance.ensureUpcomingPartitions();
        maintenance.ensureUpcomingPartitions();

        List<String> partitions = JdbcClient.create(service).sql("""
                        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'audit_records'::regclass AND c.relname IN (:names)
                        ORDER BY c.relname""")
                .param("names", List.of("audit_records_y2031m12", "audit_records_y2032m01"))
                .query(String.class)
                .list();
        assertEquals(List.of("audit_records_y2031m12", "audit_records_y2032m01"), partitions);
    }

    @Test
    void aDatabaseErrorIsLoggedAndDoesNotStopTheScheduler() {
        var unreachable = JdbcClient.create(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/audit_db"));
        var maintenance = new AuditPartitionMaintenance(unreachable, Clock.systemUTC());

        assertDoesNotThrow(maintenance::ensureUpcomingPartitions);
    }
}

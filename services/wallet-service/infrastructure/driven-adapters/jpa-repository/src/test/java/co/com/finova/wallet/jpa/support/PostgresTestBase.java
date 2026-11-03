package co.com.finova.wallet.jpa.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Real PostgreSQL 16 (same image as docker-compose.yml) shared by every test class.
 * The container starts once per JVM; Flyway applies the real migrations from the classpath.
 */
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        DATA_SOURCE = dataSource;
    }

    protected final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);

    @BeforeEach
    void cleanDatabase() {
        // The ledger trigger rejects TRUNCATE; replica mode skips triggers for this cleanup only.
        jdbc.execute("""
                SET session_replication_role = replica;
                TRUNCATE outbox_messages, processed_messages, transfer_settlements, ledger_entries, wallets;
                SET session_replication_role = DEFAULT;
                """);
    }
}

package co.com.finova.audit.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * audit_db in a container, migrated with V1, and the two ways to connect to it: as the service does
 * (login role, then SET ROLE audit_rw) and as the owner, which is a superuser in the container.
 */
public final class AuditDatabase {

    public static final String OWNER = "audit";

    private AuditDatabase() {
    }

    public static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("audit_db")
                .withUsername(OWNER)
                .withPassword(OWNER);
    }

    public static void migrate(PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    /** Same pool settings as application.yaml: every connection starts with SET ROLE audit_rw. */
    public static HikariDataSource asService(PostgreSQLContainer<?> postgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setConnectionInitSql("SET ROLE audit_rw");
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    public static DataSource asOwner(PostgreSQLContainer<?> postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}

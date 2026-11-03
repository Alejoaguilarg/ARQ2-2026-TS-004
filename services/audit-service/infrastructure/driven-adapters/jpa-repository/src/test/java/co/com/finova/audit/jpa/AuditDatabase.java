package co.com.finova.audit.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * audit_db in a container, migrated like application.yaml does, and the two ways to connect to it: as the
 * service does (its own login role, INSERT and SELECT only) and as the owner, which is a superuser in the container.
 */
public final class AuditDatabase {

    public static final String OWNER = "audit";
    public static final String SERVICE = "audit_app";

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
                .placeholders(java.util.Map.of("appUser", SERVICE, "appPassword", SERVICE))
                .load()
                .migrate();
    }

    /** Same login as application.yaml: the service's own role, which inherits audit_rw and nothing else. */
    public static HikariDataSource asService(PostgreSQLContainer<?> postgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(SERVICE);
        config.setPassword(SERVICE);
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    public static DataSource asOwner(PostgreSQLContainer<?> postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}

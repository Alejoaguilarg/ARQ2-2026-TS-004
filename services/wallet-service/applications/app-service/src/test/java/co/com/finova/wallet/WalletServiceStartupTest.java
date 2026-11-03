package co.com.finova.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

// Only PostgreSQL here: the RabbitMQ listeners and the outbox relay stay off.
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "adapters.outbox.relay-enabled=false"})
@Testcontainers
class WalletServiceStartupTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void contextStartsAndAppliesMigrations() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version, success FROM flyway_schema_history WHERE version = '1'")) {
            assertThat(rs.next()).as("V1 recorded in flyway_schema_history").isTrue();
            assertThat(rs.getBoolean("success")).isTrue();
        }
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }
}

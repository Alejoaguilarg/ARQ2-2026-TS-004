package co.com.finova.transfer.jpa.transaction;

import co.com.finova.transfer.jpa.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringTransactionRunnerTest extends PostgresTestBase {

    private final SpringTransactionRunner runner =
            new SpringTransactionRunner(new DataSourceTransactionManager(jdbc.getDataSource()));

    @Test
    void commitsAndReturnsTheResult() {
        String result = runner.inTransaction(() -> {
            markProcessed();
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void anExceptionRollsBackEverything() {
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> runner.inTransaction(() -> {
            markProcessed();
            markProcessed();
            throw failure;
        })).isSameAs(failure);

        assertThat(count()).isZero();
    }

    private void markProcessed() {
        jdbc.update("INSERT INTO processed_messages (message_id, consumer, processed_at) VALUES (?, 'c', now())",
                UUID.randomUUID());
    }

    private Integer count() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class);
    }
}

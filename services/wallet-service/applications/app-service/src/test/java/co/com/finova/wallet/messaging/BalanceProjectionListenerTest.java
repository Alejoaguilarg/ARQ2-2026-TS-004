package co.com.finova.wallet.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wallet.balance-projection.q through a real broker (spec §4.2, §4.5): BalanceChanged reaches the Redis read
 * model; a Redis outage is a technical error retried through .retry (5 s) and dead-lettered after 3 retries.
 */
@SpringBootTest
class BalanceProjectionListenerTest extends MessagingTestBase {

    private static final String QUEUE = "wallet.balance-projection.q";
    private static final String DLQ = "wallet.balance-projection.q.dlq";

    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void F2_3_balanceChangedThroughBrokerUpdatesRedis() {
        publishBalanceChanged("350000.00", 4);

        await("balance:{userId} written", Duration.ofSeconds(10),
                () -> "4".equals(redis.opsForHash().get("balance:" + userId, "version")));
        Map<Object, Object> hash = redis.opsForHash().entries("balance:" + userId);
        assertThat(hash).containsEntry("walletId", walletId.toString())
                .containsEntry("available", "350000.00")
                .containsEntry("currency", "COP");
    }

    @Test
    void F2_3_olderVersionThroughBrokerIsDiscarded() {
        publishBalanceChanged("350000.00", 4);
        await("version 4", Duration.ofSeconds(10),
                () -> "4".equals(redis.opsForHash().get("balance:" + userId, "version")));

        publishBalanceChanged("500000.00", 3);

        await("the older event consumed", Duration.ofSeconds(10), () -> messageCount(QUEUE) == 0);
        assertThat(redis.opsForHash().get("balance:" + userId, "available")).isEqualTo("350000.00");
        assertThat(messageCount(DLQ)).isZero();
    }

    @Test
    void Retry_projectionGoesToDlqAfterThreeRetries() {
        String containerId = REDIS.getContainerId();
        REDIS.getDockerClient().pauseContainerCmd(containerId).exec();
        Message dead;
        try {
            publishBalanceChanged("350000.00", 4);
            // 4 deliveries with a 5 s wait in .retry between them.
            dead = receive(DLQ, Duration.ofSeconds(40));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(containerId).exec();
            await("Redis answering again", Duration.ofSeconds(30), this::redisAnswers);
        }

        assertThat(dead).as("message in the DLQ").isNotNull();
        assertThat(dead.getMessageProperties().<String>getHeader("x-wallet-error"))
                .startsWith("BalanceReadModelUnavailableException");
        assertThat(rejectedCount(dead)).isEqualTo(3);
    }

    @Test
    void Dlq_commandOnTheProjectionQueueGoesToDlq() {
        String messageId = UUID.randomUUID().toString();
        UUID transferId = UUID.randomUUID();

        publish("", QUEUE, messageId, envelope(messageId, "DebitWallet", UUID.randomUUID(), transferId,
                "transfer-service", debitWallet(transferId, userId, "10.00")));

        Message dead = receive(DLQ, Duration.ofSeconds(10));
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().<String>getHeader("x-wallet-error")).contains("DebitWallet");
    }

    private void publishBalanceChanged(String available, long version) {
        String messageId = UUID.randomUUID().toString();
        String payload = """
                {"walletId":"%s","userId":"%s","available":"%s","version":%d}"""
                .formatted(walletId, userId, available, version);
        publish("wallet.events", "wallet.balance-changed", messageId,
                envelope(messageId, "BalanceChanged", UUID.randomUUID(), UUID.randomUUID(), "wallet-service",
                        payload));
    }

    private boolean redisAnswers() {
        try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            return "PONG".equals(connection.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static long rejectedCount(Message message) {
        List<Map<String, ?>> xDeath = (List<Map<String, ?>>) message.getMessageProperties().getHeaders()
                .get("x-death");
        return xDeath.stream()
                .filter(entry -> QUEUE.equals(entry.get("queue")) && "rejected".equals(entry.get("reason")))
                .mapToLong(entry -> ((Number) entry.get("count")).longValue())
                .sum();
    }
}

package co.com.finova.wallet.scenario;

import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.balance.gateways.BalanceReadModel;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.message.payload.CreditWallet;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.ReverseDebit;
import co.com.finova.wallet.model.message.payload.ReverseReason;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;
import co.com.finova.wallet.usecase.creditwallet.CreditWalletUseCase;
import co.com.finova.wallet.usecase.debitwallet.DebitWalletUseCase;
import co.com.finova.wallet.usecase.projectbalance.ProjectBalanceUseCase;
import co.com.finova.wallet.usecase.reversedebit.ReverseDebitUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §5 Feature 2 (F2.1–F2.5) and §3.7 through the real HTTP endpoint, PostgreSQL 16 and Redis 7.
 * F2.6 (Redis failing) lives in BalanceRedisFailureScenariosTest because it stops Redis.
 */
// No RabbitMQ here: the use cases are called directly, so the listeners and the outbox relay stay off.
// Redis gets 2 s instead of the production 200 ms: these scenarios do not measure latency (F2.6 lives in
// BalanceRedisFailureScenariosTest) and a loaded Docker Desktop can exceed 200 ms.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "adapters.outbox.relay-enabled=false",
                "spring.data.redis.timeout=2s", "spring.data.redis.connect-timeout=2s"})
@Testcontainers
class BalanceScenariosTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/wallets/me/balance";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private DebitWalletUseCase debitWallet;

    @Autowired
    private CreditWalletUseCase creditWallet;

    @Autowired
    private ReverseDebitUseCase reverseDebit;

    @Autowired
    private ProjectBalanceUseCase projectBalance;

    @Autowired
    private BalanceReadModel readModel;

    @Autowired
    private WalletRepository wallets;

    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void cleanStores() {
        // The ledger trigger rejects TRUNCATE; replica mode skips triggers for this cleanup only.
        jdbc.execute("""
                SET session_replication_role = replica;
                TRUNCATE outbox_messages, processed_messages, transfer_settlements, ledger_entries, wallets;
                SET session_replication_role = DEFAULT;
                """);
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    // ---------------------------------------------------------------- F2.1

    @Test
    void F2_1_balanceServedFromRedisWithoutQueryingPostgres() throws Exception {
        givenWallet(userId, "500000.00");
        UUID cachedWalletId = UUID.randomUUID();
        // Redis holds a value that PostgreSQL does not have: if the answer is this one, wallet_db was not read.
        redis.<String, String>opsForHash().putAll("balance:" + userId, Map.of(
                "walletId", cachedWalletId.toString(),
                "available", "850000.00",
                "currency", "COP",
                "version", "42",
                "asOf", "2026-10-19T15:00:00.120Z"));

        ResponseEntity<String> response = get(userId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("walletId", "available", "currency", "version", "asOf");
        assertThat(body.get("walletId").asText()).isEqualTo(cachedWalletId.toString());
        assertThat(body.get("available").isTextual()).as("money is a string (§1.5)").isTrue();
        assertThat(body.get("available").asText()).isEqualTo("850000.00");
        assertThat(body.get("currency").asText()).isEqualTo("COP");
        assertThat(body.get("version").isIntegralNumber()).isTrue();
        assertThat(body.get("version").asLong()).isEqualTo(42);
        assertThat(body.get("asOf").asText()).isEqualTo("2026-10-19T15:00:00.120Z");
    }

    // ---------------------------------------------------------------- F2.2

    @Test
    void F2_2_cacheMissReadsPostgresAndStoresWithVersion() throws Exception {
        UUID walletId = givenWallet(userId, "500000.00");
        jdbc.update("UPDATE wallets SET version = 3 WHERE wallet_id = ?", walletId);

        ResponseEntity<String> response = get(userId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("walletId").asText()).isEqualTo(walletId.toString());
        assertThat(body.get("available").asText()).isEqualTo("500000.00");
        assertThat(body.get("currency").asText()).isEqualTo("COP");
        assertThat(body.get("version").asLong()).isEqualTo(3);
        assertThat(Instant.parse(body.get("asOf").asText())).isEqualTo(updatedAt(userId));

        Map<String, String> cached = redis.<String, String>opsForHash().entries("balance:" + userId);
        assertThat(cached).containsEntry("walletId", walletId.toString())
                .containsEntry("available", "500000.00")
                .containsEntry("currency", "COP")
                .containsEntry("version", "3")
                .containsEntry("asOf", body.get("asOf").asText());
    }

    // ---------------------------------------------------------------- F2.3

    @Test
    void F2_3_staleBalanceChangedIsDiscarded() {
        UUID walletId = UUID.randomUUID();
        projectBalance.handle(balanceChanged(walletId, userId, "100000.00", 9));

        projectBalance.handle(balanceChanged(walletId, userId, "700000.00", 7));

        Map<String, String> cached = redis.<String, String>opsForHash().entries("balance:" + userId);
        assertThat(cached).containsEntry("version", "9").containsEntry("available", "100000.00");
    }

    // ---------------------------------------------------------------- F2.4

    @Test
    void F2_4_cacheWarmUpDoesNotOverwriteNewerProjection() throws Exception {
        UUID walletId = givenWallet(userId, "500000.00");
        jdbc.update("UPDATE wallets SET version = 8 WHERE wallet_id = ?", walletId);
        // The cache-miss path reads version 8 from wallet_db...
        Wallet readByWarmUp = wallets.findByUserId(userId).orElseThrow();
        // ...meanwhile the projection writes version 9...
        projectBalance.handle(balanceChanged(walletId, userId, "350000.00", 9));
        // ...and then the warm-up writes version 8 with the same compare-and-set it always uses.
        boolean written = readModel.storeIfNewer(BalanceSnapshot.fromWallet(readByWarmUp));

        assertThat(written).isFalse();
        Map<String, String> cached = redis.<String, String>opsForHash().entries("balance:" + userId);
        assertThat(cached).containsEntry("version", "9").containsEntry("available", "350000.00");
        assertThat(JSON.readTree(get(userId).getBody()).get("version").asLong()).isEqualTo(9);
    }

    // ---------------------------------------------------------------- F2.5

    @Test
    void F2_5_completedTransferUpdatesBothCachedBalances() throws Exception {
        UUID senderId = userId;
        UUID recipientId = UUID.randomUUID();
        givenWallet(senderId, "500000.00");
        givenWallet(recipientId, "100000.00");
        assertThat(JSON.readTree(get(senderId).getBody()).get("version").asLong()).isZero();
        assertThat(JSON.readTree(get(recipientId).getBody()).get("version").asLong()).isZero();

        UUID transferId = UUID.randomUUID();
        debitWallet.handle(envelope(MessageType.DEBIT_WALLET, transferId,
                new DebitWallet(transferId, senderId, Money.parse("150000.00"), Currency.COP)));
        creditWallet.handle(envelope(MessageType.CREDIT_WALLET, transferId,
                new CreditWallet(transferId, recipientId, Money.parse("150000.00"), Currency.COP, 1)));
        balanceChangedFromOutbox().forEach(projectBalance::handle);

        JsonNode sender = JSON.readTree(get(senderId).getBody());
        assertThat(sender.get("available").asText()).isEqualTo("350000.00");
        assertThat(sender.get("version").asLong()).isEqualTo(1);
        assertThat(Instant.parse(sender.get("asOf").asText())).isEqualTo(updatedAt(senderId));
        JsonNode recipient = JSON.readTree(get(recipientId).getBody());
        assertThat(recipient.get("available").asText()).isEqualTo("250000.00");
        assertThat(recipient.get("version").asLong()).isEqualTo(1);
        assertThat(Instant.parse(recipient.get("asOf").asText())).isEqualTo(updatedAt(recipientId));
    }

    // ---------------------------------------------------------------- asOf decision (2026-11-02)

    @Test
    void debitCreditAndReversalStoreUpdatedAtEqualToBalanceChangedOccurredAt() throws Exception {
        UUID senderId = userId;
        UUID recipientId = UUID.randomUUID();
        givenWallet(senderId, "500000.00");
        givenWallet(recipientId, "100000.00");

        UUID completed = UUID.randomUUID();
        debitWallet.handle(envelope(MessageType.DEBIT_WALLET, completed,
                new DebitWallet(completed, senderId, Money.parse("150000.00"), Currency.COP)));
        assertOccurredAtEqualsUpdatedAt(senderId, 1);

        creditWallet.handle(envelope(MessageType.CREDIT_WALLET, completed,
                new CreditWallet(completed, recipientId, Money.parse("150000.00"), Currency.COP, 1)));
        assertOccurredAtEqualsUpdatedAt(recipientId, 1);

        UUID reversed = UUID.randomUUID();
        debitWallet.handle(envelope(MessageType.DEBIT_WALLET, reversed,
                new DebitWallet(reversed, senderId, Money.parse("50000.00"), Currency.COP)));
        reverseDebit.handle(envelope(MessageType.REVERSE_DEBIT, reversed,
                new ReverseDebit(reversed, senderId, Money.parse("50000.00"), Currency.COP,
                        ReverseReason.CREDIT_TIMEOUT)));
        assertOccurredAtEqualsUpdatedAt(senderId, 3);
    }

    // ---------------------------------------------------------------- §3.7 and identity header decision

    @Test
    void walletNotFoundAnswers404WithErrorResponse() throws Exception {
        ResponseEntity<String> response = get(userId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertErrorResponse(response, "WALLET_NOT_FOUND", correlationId.toString());
        assertThat(redis.hasKey("balance:" + userId)).isFalse();
    }

    @Test
    void missingUserIdHeaderAnswersInternalError() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId.toString());

        ResponseEntity<String> response = http.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorResponse(response, "INTERNAL_ERROR", correlationId.toString());
    }

    @Test
    void invalidUserIdHeaderAnswersInternalError() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "not-a-uuid");

        ResponseEntity<String> response = http.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertErrorResponse(response, "INTERNAL_ERROR", null);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<String> get(UUID user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        headers.set("X-Correlation-Id", correlationId.toString());
        return http.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private UUID givenWallet(UUID user, String opening) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, ?, 'COP', 0, now())
                """, walletId, user, new BigDecimal(opening));
        jdbc.update("""
                INSERT INTO ledger_entries (entry_id, wallet_id, transfer_id, type, amount, balance_after, created_at)
                VALUES (?, ?, NULL, 'OPENING', ?, ?, now())
                """, UUID.randomUUID(), walletId, new BigDecimal(opening), new BigDecimal(opening));
        return walletId;
    }

    private Instant updatedAt(UUID user) {
        return jdbc.queryForObject("SELECT updated_at FROM wallets WHERE user_id = ?", OffsetDateTime.class, user)
                .toInstant();
    }

    private void assertOccurredAtEqualsUpdatedAt(UUID user, long version) throws Exception {
        JsonNode envelope = balanceChangedEnvelopes().stream()
                .filter(e -> e.get("payload").get("userId").asText().equals(user.toString()))
                .filter(e -> e.get("payload").get("version").asLong() == version)
                .findFirst().orElseThrow();
        assertThat(Instant.parse(envelope.get("occurredAt").asText())).isEqualTo(updatedAt(user));
    }

    private List<JsonNode> balanceChangedEnvelopes() throws Exception {
        List<JsonNode> envelopes = new ArrayList<>();
        for (String payload : jdbc.queryForList(
                "SELECT payload::text FROM outbox_messages WHERE payload->>'type' = 'BalanceChanged'", String.class)) {
            envelopes.add(JSON.readTree(payload));
        }
        return envelopes;
    }

    /** What the stage 6 consumer will hand to the projection: the BalanceChanged rows of the outbox. */
    private List<MessageEnvelope<BalanceChanged>> balanceChangedFromOutbox() throws Exception {
        List<MessageEnvelope<BalanceChanged>> events = new ArrayList<>();
        for (JsonNode envelope : balanceChangedEnvelopes()) {
            JsonNode payload = envelope.get("payload");
            events.add(new MessageEnvelope<>(
                    UUID.fromString(envelope.get("messageId").asText()),
                    MessageType.BALANCE_CHANGED,
                    UUID.fromString(envelope.get("correlationId").asText()),
                    UUID.fromString(envelope.get("transferId").asText()),
                    envelope.get("producer").asText(),
                    Instant.parse(envelope.get("occurredAt").asText()),
                    new BalanceChanged(
                            UUID.fromString(payload.get("walletId").asText()),
                            UUID.fromString(payload.get("userId").asText()),
                            Money.parse(payload.get("available").asText()),
                            payload.get("version").asLong())));
        }
        return events;
    }

    private MessageEnvelope<BalanceChanged> balanceChanged(UUID walletId, UUID user, String available, long version) {
        return MessageEnvelope.create(UUID.randomUUID(), correlationId, UUID.randomUUID(), Instant.now(),
                new BalanceChanged(walletId, user, Money.parse(available), version));
    }

    private <P extends co.com.finova.wallet.model.message.payload.MessagePayload> MessageEnvelope<P> envelope(
            MessageType type, UUID transferId, P payload) {
        return new MessageEnvelope<>(UUID.randomUUID(), type, correlationId, transferId, "transfer-service",
                Instant.now(), payload);
    }

    private static void assertErrorResponse(ResponseEntity<String> response, String code, String correlationId)
            throws Exception {
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("code", "message", "correlationId", "timestamp",
                "details");
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("message").asText()).isNotBlank();
        if (correlationId == null) {
            assertThat(body.get("correlationId").isNull()).isTrue();
        } else {
            assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
        }
        assertThat(Instant.parse(body.get("timestamp").asText())).isNotNull();
        assertThat(body.get("details").isObject()).isTrue();
        assertThat(body.get("details").isEmpty()).isTrue();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

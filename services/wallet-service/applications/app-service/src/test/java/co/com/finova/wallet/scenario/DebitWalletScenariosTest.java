package co.com.finova.wallet.scenario;

import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.usecase.debitwallet.DebitWalletUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §5 scenarios of the debit (§4.7), run through the real use case against PostgreSQL 16. */
// No RabbitMQ here: the use cases are called directly, so the listeners and the outbox relay stay off.
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "adapters.outbox.relay-enabled=false"})
@Testcontainers
class DebitWalletScenariosTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private DebitWalletUseCase debitWallet;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private UUID walletId;

    @BeforeEach
    void cleanDatabase() {
        // The ledger trigger rejects TRUNCATE; replica mode skips triggers for this cleanup only.
        jdbc.execute("""
                SET session_replication_role = replica;
                TRUNCATE outbox_messages, processed_messages, transfer_settlements, ledger_entries, wallets;
                SET session_replication_role = DEFAULT;
                """);
    }

    // ---------------------------------------------------------------- F4.1 (debit part)

    @Test
    void F4_1_debitAppliedWritesLedgerResultBalanceChangedAndAudit() throws Exception {
        givenWallet("500000.00");
        MessageEnvelope<DebitWallet> command = command(UUID.randomUUID(), "150000.00");

        debitWallet.handle(command);

        assertBalance("350000.00", 1);
        assertThat(ledgerRows("DEBIT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance_after FROM ledger_entries WHERE transfer_id = ?",
                BigDecimal.class, transferOf(command))).isEqualByComparingTo("350000.00");
        assertThat(storedResultType(command)).isEqualTo("WalletDebited");
        assertThat(outboxTypes()).containsExactlyInAnyOrder("WalletDebited", "BalanceChanged", "AttemptRecorded");

        JsonNode debited = outboxPayload("WalletDebited");
        assertThat(debited.get("walletId").asText()).isEqualTo(walletId.toString());
        assertThat(debited.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(debited.get("amount").asText()).isEqualTo("150000.00");
        assertThat(debited.get("version").asLong()).isEqualTo(1);
        assertThat(outboxPayload("BalanceChanged").get("available").asText()).isEqualTo("350000.00");
        JsonNode audit = outboxPayload("AttemptRecorded");
        assertThat(audit.get("stage").asText()).isEqualTo("DEBIT");
        assertThat(audit.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(audit.get("reason").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- F4.2

    @Test
    void F4_2_insufficientFundsRejectsWithoutLedgerEntry() throws Exception {
        givenWallet("50000.00");
        MessageEnvelope<DebitWallet> command = command(UUID.randomUUID(), "80000.00");

        debitWallet.handle(command);

        assertBalance("50000.00", 0);
        assertThat(ledgerRows("DEBIT")).isZero();
        assertThat(storedResultType(command)).isEqualTo("DebitRejected");
        assertThat(outboxTypes()).containsExactlyInAnyOrder("DebitRejected", "AttemptRecorded");
        assertThat(outboxPayload("DebitRejected").get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        JsonNode audit = outboxPayload("AttemptRecorded");
        assertThat(audit.get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(audit.get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void F4_2_missingSenderWalletRejectsWithSenderWalletNotFound() throws Exception {
        MessageEnvelope<DebitWallet> command = command(UUID.randomUUID(), "10.00");

        debitWallet.handle(command);

        assertThat(ledgerRows("DEBIT")).isZero();
        assertThat(outboxTypes()).containsExactlyInAnyOrder("DebitRejected", "AttemptRecorded");
        assertThat(outboxPayload("DebitRejected").get("reason").asText()).isEqualTo("SENDER_WALLET_NOT_FOUND");
        assertThat(outboxPayload("AttemptRecorded").get("reason").asText()).isEqualTo("SENDER_WALLET_NOT_FOUND");
    }

    // ---------------------------------------------------------------- F4.8

    @Test
    void F4_8_duplicateDebitWalletRepublishesStoredWalletDebited() throws Exception {
        givenWallet("500000.00");
        MessageEnvelope<DebitWallet> command = command(UUID.randomUUID(), "150000.00");
        debitWallet.handle(command);
        JsonNode original = outboxPayload("WalletDebited");

        debitWallet.handle(command);

        assertBalance("350000.00", 1);
        assertThat(ledgerRows("DEBIT")).isEqualTo(1);
        assertThat(count("processed_messages")).isEqualTo(1);
        assertThat(outboxTypes()).containsExactlyInAnyOrder(
                "WalletDebited", "BalanceChanged", "AttemptRecorded", "WalletDebited");
        List<JsonNode> debited = outboxEnvelopes("WalletDebited");
        assertThat(debited).extracting(envelope -> envelope.get("payload")).containsOnly(original);
        assertThat(debited).extracting(envelope -> envelope.get("messageId").asText())
                .doesNotHaveDuplicates()
                .doesNotContain(command.messageId().toString());
    }

    // ---------------------------------------------------------------- F4.9

    @Test
    void F4_9_concurrentDebitsNeverOverdraw() throws Exception {
        givenWallet("150000.00");
        int debits = 10;
        List<MessageEnvelope<DebitWallet>> commands = new ArrayList<>();
        for (int i = 0; i < debits; i++) {
            commands.add(command(UUID.randomUUID(), "100000.00"));
        }

        List<Throwable> failures = runConcurrently(commands);

        assertThat(failures).isEmpty();
        assertBalance("50000.00", 1);
        assertThat(ledgerRows("DEBIT")).isEqualTo(1);
        assertThat(outboxTypes()).filteredOn("WalletDebited"::equals).hasSize(1);
        assertThat(outboxEnvelopes("DebitRejected")).hasSize(debits - 1)
                .allSatisfy(envelope -> assertThat(envelope.get("payload").get("reason").asText())
                        .isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(jdbc.queryForObject("SELECT min(balance) >= 0 FROM wallets", Boolean.class)).isTrue();
    }

    // ---------------------------------------------------------------- F4.12

    @Test
    void F4_12_republishedRejectedDebitStaysRejectedAfterReceivingMoney() throws Exception {
        givenWallet("50000.00");
        MessageEnvelope<DebitWallet> command = command(UUID.randomUUID(), "80000.00");
        debitWallet.handle(command);
        // The sender receives money afterwards (credits arrive in stage 4; set directly here).
        jdbc.update("UPDATE wallets SET balance = 900000.00, version = version + 1 WHERE wallet_id = ?", walletId);

        debitWallet.handle(command);

        assertBalance("900000.00", 1);
        assertThat(ledgerRows("DEBIT")).isZero();
        assertThat(outboxEnvelopes("DebitRejected")).hasSize(2)
                .allSatisfy(envelope -> assertThat(envelope.get("payload").get("reason").asText())
                        .isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(outboxTypes()).filteredOn("AttemptRecorded"::equals).hasSize(1);
    }

    // ---------------------------------------------------------------- F2.7

    @Test
    void F2_7_staleCacheNeverAuthorizesDebit() throws Exception {
        // Redis would show 100000.00; the use case only trusts the conditional update in PostgreSQL.
        givenWallet("50000.00");

        debitWallet.handle(command(UUID.randomUUID(), "80000.00"));

        assertBalance("50000.00", 0);
        assertThat(ledgerRows("DEBIT")).isZero();
        assertThat(outboxPayload("DebitRejected").get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    // ---------------------------------------------------------------- F3.3 (Wallet part)

    @Test
    void F3_3_tenDeliveriesOfSameCommandLeaveExactlyOneDebit() throws Exception {
        givenWallet("500000.00");
        MessageEnvelope<DebitWallet> command = command(UUID.randomUUID(), "150000.00");
        List<MessageEnvelope<DebitWallet>> deliveries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            deliveries.add(command);
        }

        // Deliveries that collide are rolled back and would be retried from the queue (§4.2).
        runConcurrently(deliveries);
        debitWallet.handle(command);

        assertBalance("350000.00", 1);
        assertThat(ledgerRows("DEBIT")).isEqualTo(1);
        assertThat(count("processed_messages")).isEqualTo(1);
        assertThat(outboxTypes()).filteredOn("AttemptRecorded"::equals).hasSize(1);
        assertThat(outboxTypes()).filteredOn("BalanceChanged"::equals).hasSize(1);
    }

    // ---------------------------------------------------------------- decision taken with the team lead

    @Test
    void debitWithNewMessageIdForAlreadyDebitedTransferRollsBack() {
        givenWallet("500000.00");
        UUID transferId = UUID.randomUUID();
        debitWallet.handle(command(transferId, "150000.00"));
        MessageEnvelope<DebitWallet> sameTransfer = command(transferId, "150000.00");

        assertThatThrownBy(() -> debitWallet.handle(sameTransfer))
                .isInstanceOf(DuplicateLedgerEntryException.class);

        assertBalance("350000.00", 1);
        assertThat(ledgerRows("DEBIT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?",
                Integer.class, sameTransfer.messageId())).isZero();
        assertThat(count("processed_messages")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(3);
    }

    // ---------------------------------------------------------------- contract of the stored messages

    @Test
    void outboxJsonFollowsEnvelopeContract() throws Exception {
        givenWallet("500000.00");
        MessageEnvelope<DebitWallet> command = command(UUID.randomUUID(), "150000.00");

        debitWallet.handle(command);

        List<java.util.Map<String, Object>> rows =
                jdbc.queryForList("SELECT id, exchange, routing_key, payload::text AS payload FROM outbox_messages");
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            JsonNode envelope = read((String) row.get("payload"));
            List<String> keys = new ArrayList<>();
            envelope.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactlyInAnyOrder(
                    "messageId", "type", "correlationId", "transferId", "producer", "occurredAt", "payload");
            assertThat(envelope.get("messageId").asText()).isEqualTo(row.get("id").toString());
            assertThat(envelope.get("correlationId").asText()).isEqualTo(correlationId.toString());
            assertThat(envelope.get("transferId").asText()).isEqualTo(transferOf(command).toString());
            assertThat(envelope.get("producer").asText()).isEqualTo("wallet-service");
            assertThat(Instant.parse(envelope.get("occurredAt").asText())).isNotNull();
            assertThat(envelope.get("occurredAt").asText()).endsWith("Z");
            var destination = MessageType.fromContractName(envelope.get("type").asText()).publishDestination();
            assertThat(row.get("exchange")).isEqualTo(destination.exchange());
            assertThat(row.get("routing_key")).isEqualTo(destination.routingKey());
        });

        assertThat(outboxPayload("WalletDebited").get("amount").isTextual()).isTrue();
        JsonNode audit = outboxPayload("AttemptRecorded");
        assertThat(audit.has("httpStatus")).isTrue();
        assertThat(audit.get("httpStatus").isNull()).isTrue();
        assertThat(audit.has("statusCode")).isFalse();
        assertThat(audit.get("amount").asText()).isEqualTo("150000.00");
        assertThat(audit.get("eventId").asText()).isEqualTo(outboxEnvelopes("AttemptRecorded").get(0)
                .get("messageId").asText());
    }

    // ---------------------------------------------------------------- helpers

    private void givenWallet(String balance) {
        walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, ?, 'COP', 0, now())
                """, walletId, userId, new BigDecimal(balance));
    }

    private MessageEnvelope<DebitWallet> command(UUID transferId, String amount) {
        DebitWallet payload = new DebitWallet(transferId, userId, Money.parse(amount), Currency.COP);
        return new MessageEnvelope<>(UUID.randomUUID(), MessageType.DEBIT_WALLET, correlationId, transferId,
                "transfer-service", Instant.now(), payload);
    }

    private static UUID transferOf(MessageEnvelope<DebitWallet> command) {
        return command.payload().transferId();
    }

    private List<Throwable> runConcurrently(List<MessageEnvelope<DebitWallet>> commands) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(commands.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (MessageEnvelope<DebitWallet> command : commands) {
                Callable<Throwable> task = () -> {
                    start.await();
                    try {
                        debitWallet.handle(command);
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> result : results) {
                Throwable failure = result.get(60, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertBalance(String balance, long version) {
        var row = jdbc.queryForMap("SELECT balance, version FROM wallets WHERE wallet_id = ?", walletId);
        assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo(balance);
        assertThat(row.get("version")).isEqualTo(version);
    }

    private int ledgerRows(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE type = ?", Integer.class, type);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String storedResultType(MessageEnvelope<DebitWallet> command) {
        return jdbc.queryForObject("SELECT result_type FROM processed_messages WHERE message_id = ? AND consumer = ?",
                String.class, command.messageId(), DebitWalletUseCase.CONSUMER);
    }

    private List<String> outboxTypes() {
        return jdbc.queryForList("SELECT payload->>'type' FROM outbox_messages", String.class);
    }

    private List<JsonNode> outboxEnvelopes(String type) {
        return jdbc.queryForList("SELECT payload::text FROM outbox_messages WHERE payload->>'type' = ?",
                String.class, type).stream().map(DebitWalletScenariosTest::read).toList();
    }

    private JsonNode outboxPayload(String type) {
        List<JsonNode> envelopes = outboxEnvelopes(type);
        assertThat(envelopes).as("outbox rows of type " + type).hasSize(1);
        return envelopes.get(0).get("payload");
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

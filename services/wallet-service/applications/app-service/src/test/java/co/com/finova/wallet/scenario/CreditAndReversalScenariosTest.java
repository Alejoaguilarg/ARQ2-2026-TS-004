package co.com.finova.wallet.scenario;

import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.ledger.DebitMismatchException;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.CreditWallet;
import co.com.finova.wallet.model.message.payload.DebitWallet;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.message.payload.ReverseDebit;
import co.com.finova.wallet.model.message.payload.ReverseReason;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.usecase.creditwallet.CreditWalletUseCase;
import co.com.finova.wallet.usecase.debitwallet.DebitWalletUseCase;
import co.com.finova.wallet.usecase.reversedebit.ReverseDebitUseCase;
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

/** Spec §5 scenarios of the credit and the reversal (§4.7), run through the real use cases on PostgreSQL 16. */
// No RabbitMQ here: the use cases are called directly, so the listeners and the outbox relay stay off.
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "adapters.outbox.relay-enabled=false"})
@Testcontainers
class CreditAndReversalScenariosTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_db");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private DebitWalletUseCase debitWallet;

    @Autowired
    private CreditWalletUseCase creditWallet;

    @Autowired
    private ReverseDebitUseCase reverseDebit;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private UUID senderWallet;
    private UUID recipientWallet;

    @BeforeEach
    void provisionedWallets() {
        jdbc.execute("""
                SET session_replication_role = replica;
                TRUNCATE outbox_messages, processed_messages, transfer_settlements, ledger_entries, wallets;
                SET session_replication_role = DEFAULT;
                """);
        senderWallet = provision(senderId, "500000.00");
        recipientWallet = provision(recipientId, "100000.00");
    }

    // ---------------------------------------------------------------- F4.1 (credit part)

    @Test
    void F4_1_creditAppliedWritesSettlementLedgerResultBalanceChangedAndAudit() throws Exception {
        UUID transferId = debited("150000.00");
        MessageEnvelope<CreditWallet> command = credit(transferId, 1, "150000.00");

        creditWallet.handle(command);

        assertBalance(senderWallet, "350000.00");
        assertBalance(recipientWallet, "250000.00");
        assertThat(settlement(transferId)).isEqualTo("CREDITED");
        assertThat(ledgerTypes(transferId)).containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(storedResultType(command)).isEqualTo("WalletCredited");
        assertThat(outboxTypes(transferId)).containsExactlyInAnyOrder(
                "WalletDebited", "BalanceChanged", "AttemptRecorded",
                "WalletCredited", "BalanceChanged", "AttemptRecorded");
        JsonNode credited = single(transferId, "WalletCredited");
        assertThat(credited.get("walletId").asText()).isEqualTo(recipientWallet.toString());
        assertThat(credited.get("userId").asText()).isEqualTo(recipientId.toString());
        assertThat(credited.get("amount").asText()).isEqualTo("150000.00");
        assertThat(credited.get("version").asLong()).isEqualTo(1);
        assertThat(audits(transferId, "CREDIT")).singleElement().satisfies(audit -> {
            assertThat(audit.get("outcome").asText()).isEqualTo("APPLIED");
            assertThat(audit.get("userId").asText()).isEqualTo(recipientId.toString());
        });
        assertLedgerMatchesBalances();
        assertTotal("600000.00");
    }

    @Test
    void F4_1_laterCreditAttemptReemitsOriginalWalletCreditedWithoutAudit() throws Exception {
        UUID transferId = debited("150000.00");
        creditWallet.handle(credit(transferId, 1, "150000.00"));
        JsonNode original = single(transferId, "WalletCredited");

        creditWallet.handle(credit(transferId, 2, "150000.00"));

        assertBalance(recipientWallet, "250000.00");
        assertThat(ledgerTypes(transferId)).containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(payloads(transferId, "WalletCredited")).hasSize(2).containsOnly(original);
        assertThat(audits(transferId, "CREDIT")).hasSize(1);
        assertThat(outboxTypes(transferId)).filteredOn("BalanceChanged"::equals).hasSize(2);
    }

    // ---------------------------------------------------------------- F4.3 (Wallet part)

    @Test
    void F4_3_reverseDebitAfterCreditTimeoutRestoresSenderBalance() throws Exception {
        UUID transferId = debited("150000.00");

        reverseDebit.handle(reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT, "150000.00"));

        assertBalance(senderWallet, "500000.00");
        assertBalance(recipientWallet, "100000.00");
        assertThat(settlement(transferId)).isEqualTo("REVERSED");
        assertThat(ledgerTypes(transferId)).containsExactlyInAnyOrder("DEBIT", "REVERSAL");
        JsonNode reversed = single(transferId, "DebitReversed");
        assertThat(reversed.get("walletId").asText()).isEqualTo(senderWallet.toString());
        assertThat(reversed.get("amount").asText()).isEqualTo("150000.00");
        assertThat(reversed.get("version").asLong()).isEqualTo(2);
        assertThat(audits(transferId, "REVERSAL")).singleElement()
                .satisfies(audit -> assertThat(audit.get("outcome").asText()).isEqualTo("APPLIED"));
        assertLedgerMatchesBalances();
        assertTotal("600000.00");
    }

    // ---------------------------------------------------------------- F4.4

    @Test
    void F4_4_creditWithoutRecipientWalletFailsAndReversalRestoresSender() throws Exception {
        UUID transferId = debited("150000.00");
        UUID userWithoutWallet = UUID.randomUUID();

        creditWallet.handle(credit(transferId, userWithoutWallet, 1, "150000.00"));

        assertThat(settlement(transferId)).isNull();
        JsonNode failed = single(transferId, "CreditFailed");
        assertThat(failed.get("reason").asText()).isEqualTo("RECIPIENT_WALLET_NOT_FOUND");
        assertThat(failed.get("attempt").asInt()).isEqualTo(1);
        assertThat(audits(transferId, "CREDIT")).singleElement().satisfies(audit -> {
            assertThat(audit.get("outcome").asText()).isEqualTo("FAILED");
            assertThat(audit.get("reason").asText()).isEqualTo("RECIPIENT_WALLET_NOT_FOUND");
        });

        reverseDebit.handle(reverse(transferId, senderId, ReverseReason.RECIPIENT_WALLET_NOT_FOUND, "150000.00"));

        assertBalance(senderWallet, "500000.00");
        assertThat(settlement(transferId)).isEqualTo("REVERSED");
        assertThat(ledgerTypes(transferId)).containsExactlyInAnyOrder("DEBIT", "REVERSAL");
        assertLedgerMatchesBalances();
        assertTotal("600000.00");
    }

    // ---------------------------------------------------------------- F4.5

    @Test
    void F4_5_reverseDebitAfterAppliedCreditReemitsWalletCredited() throws Exception {
        UUID transferId = debited("150000.00");
        creditWallet.handle(credit(transferId, 1, "150000.00"));
        JsonNode original = single(transferId, "WalletCredited");
        MessageEnvelope<ReverseDebit> command = reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT,
                "150000.00");

        reverseDebit.handle(command);

        assertBalance(senderWallet, "350000.00");
        assertBalance(recipientWallet, "250000.00");
        assertThat(settlement(transferId)).isEqualTo("CREDITED");
        assertThat(ledgerTypes(transferId)).containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(storedResultType(command)).isEqualTo("WalletCredited");
        assertThat(payloads(transferId, "WalletCredited")).hasSize(2).containsOnly(original);
        assertThat(payloads(transferId, "DebitReversed")).isEmpty();
        assertThat(audits(transferId, "REVERSAL")).singleElement().satisfies(audit -> {
            assertThat(audit.get("outcome").asText()).isEqualTo("REJECTED");
            assertThat(audit.get("reason").asText()).isEqualTo("ALREADY_CREDITED");
        });
        assertTotal("600000.00");
    }

    // ---------------------------------------------------------------- F4.6

    @Test
    void F4_6_lateCreditAfterReversalFailsWithTransferReversed() throws Exception {
        UUID transferId = debited("150000.00");
        reverseDebit.handle(reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT, "150000.00"));

        creditWallet.handle(credit(transferId, 3, "150000.00"));

        assertBalance(senderWallet, "500000.00");
        assertBalance(recipientWallet, "100000.00");
        assertThat(settlement(transferId)).isEqualTo("REVERSED");
        assertThat(ledgerTypes(transferId)).containsExactlyInAnyOrder("DEBIT", "REVERSAL");
        JsonNode failed = single(transferId, "CreditFailed");
        assertThat(failed.get("reason").asText()).isEqualTo("TRANSFER_REVERSED");
        assertThat(failed.get("attempt").asInt()).isEqualTo(3);
        assertThat(audits(transferId, "CREDIT")).singleElement().satisfies(audit -> {
            assertThat(audit.get("outcome").asText()).isEqualTo("FAILED");
            assertThat(audit.get("reason").asText()).isEqualTo("TRANSFER_REVERSED");
        });
        assertTotal("600000.00");
    }

    // ---------------------------------------------------------------- F4.11

    @Test
    void F4_11_concurrentCreditAndReversalSettleExactlyOnce() throws Exception {
        int rounds = 20;
        List<UUID> transfers = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            UUID transferId = debited("1000.00");
            transfers.add(transferId);

            List<Throwable> failures = runConcurrently(List.of(
                    () -> creditWallet.handle(credit(transferId, 3, "1000.00")),
                    () -> reverseDebit.handle(reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT,
                            "1000.00"))));

            assertThat(failures).as("round " + i).isEmpty();
        }

        for (UUID transferId : transfers) {
            List<String> types = ledgerTypes(transferId);
            assertThat(types).as("ledger of " + transferId).hasSize(2).contains("DEBIT");
            String effect = types.contains("CREDIT") ? "CREDIT" : "REVERSAL";
            assertThat(types).as("never both").doesNotContain(effect.equals("CREDIT") ? "REVERSAL" : "CREDIT");
            assertThat(settlement(transferId)).isEqualTo(effect.equals("CREDIT") ? "CREDITED" : "REVERSED");
        }
        assertLedgerMatchesBalances();
        assertTotal("600000.00");
    }

    // ---------------------------------------------------------------- F4.14 (Wallet part)

    @Test
    void F4_14_moneyIsNeverLostOrCreated() throws Exception {
        // T1 completes; a later attempt and a reversal arrive afterwards.
        UUID completed = debited("100000.00");
        creditWallet.handle(credit(completed, 1, "100000.00"));
        creditWallet.handle(credit(completed, 2, "100000.00"));
        reverseDebit.handle(reverse(completed, senderId, ReverseReason.CREDIT_TIMEOUT, "100000.00"));

        // T2 is reversed by timeout; a late credit and a resent reversal arrive afterwards.
        UUID timedOut = debited("50000.00");
        MessageEnvelope<ReverseDebit> reversal = reverse(timedOut, senderId, ReverseReason.CREDIT_TIMEOUT, "50000.00");
        reverseDebit.handle(reversal);
        creditWallet.handle(credit(timedOut, 3, "50000.00"));
        reverseDebit.handle(reversal);
        reverseDebit.handle(reverse(timedOut, senderId, ReverseReason.CREDIT_TIMEOUT, "50000.00"));

        // T3 has no recipient wallet and is compensated.
        UUID noRecipient = debited("25000.00");
        creditWallet.handle(credit(noRecipient, UUID.randomUUID(), 1, "25000.00"));
        reverseDebit.handle(reverse(noRecipient, senderId, ReverseReason.RECIPIENT_WALLET_NOT_FOUND, "25000.00"));

        // T4 is rejected for insufficient funds: nothing to settle.
        debitWallet.handle(debit(UUID.randomUUID(), "9000000.00"));

        assertBalance(senderWallet, "400000.00");
        assertBalance(recipientWallet, "200000.00");
        assertThat(ledgerTypes(completed)).containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(ledgerTypes(timedOut)).containsExactlyInAnyOrder("DEBIT", "REVERSAL");
        assertThat(ledgerTypes(noRecipient)).containsExactlyInAnyOrder("DEBIT", "REVERSAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_settlements", Integer.class)).isEqualTo(3);
        assertLedgerMatchesBalances();
        assertTotal("600000.00");
    }

    // ---------------------------------------------------------------- decision 2 and repeated commands

    @Test
    void reverseDebitAlreadyReversedReemitsOriginalDebitReversedWithoutAudit() throws Exception {
        UUID transferId = debited("150000.00");
        reverseDebit.handle(reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT, "150000.00"));
        JsonNode original = single(transferId, "DebitReversed");

        reverseDebit.handle(reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT, "150000.00"));

        assertBalance(senderWallet, "500000.00");
        assertThat(ledgerTypes(transferId)).containsExactlyInAnyOrder("DEBIT", "REVERSAL");
        assertThat(payloads(transferId, "DebitReversed")).hasSize(2).containsOnly(original);
        assertThat(audits(transferId, "REVERSAL")).hasSize(1);
    }

    @Test
    void repeatedCreditCommandRepublishesStoredResult() throws Exception {
        UUID transferId = debited("150000.00");
        MessageEnvelope<CreditWallet> command = credit(transferId, 1, "150000.00");
        creditWallet.handle(command);

        creditWallet.handle(command);

        assertBalance(recipientWallet, "250000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?",
                Integer.class, command.messageId())).isEqualTo(1);
        assertThat(payloads(transferId, "WalletCredited")).hasSize(2);
        assertThat(audits(transferId, "CREDIT")).hasSize(1);
    }

    // ---------------------------------------------------------------- decision 3: a DEBIT must exist

    @Test
    void creditWithoutDebitRollsBack() {
        UUID transferId = UUID.randomUUID();
        MessageEnvelope<CreditWallet> command = credit(transferId, 1, "150000.00");

        assertThatThrownBy(() -> creditWallet.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWrittenFor(transferId, command.messageId());
        assertBalance(recipientWallet, "100000.00");
    }

    @Test
    void reverseWithoutDebitRollsBack() {
        UUID transferId = UUID.randomUUID();
        MessageEnvelope<ReverseDebit> command = reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT, "10.00");

        assertThatThrownBy(() -> reverseDebit.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertNothingWrittenFor(transferId, command.messageId());
        assertBalance(senderWallet, "500000.00");
    }

    @Test
    void reverseWithDifferentAmountRollsBack() {
        UUID transferId = debited("150000.00");
        MessageEnvelope<ReverseDebit> command = reverse(transferId, senderId, ReverseReason.CREDIT_TIMEOUT,
                "150000.01");

        assertThatThrownBy(() -> reverseDebit.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertThat(settlement(transferId)).isNull();
        assertThat(ledgerTypes(transferId)).containsExactly("DEBIT");
        assertBalance(senderWallet, "350000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?",
                Integer.class, command.messageId())).isZero();
    }

    @Test
    void reverseFromAnotherSenderRollsBack() {
        UUID transferId = debited("150000.00");
        MessageEnvelope<ReverseDebit> command = reverse(transferId, recipientId, ReverseReason.CREDIT_TIMEOUT,
                "150000.00");

        assertThatThrownBy(() -> reverseDebit.handle(command)).isInstanceOf(DebitMismatchException.class);

        assertThat(settlement(transferId)).isNull();
        assertThat(ledgerTypes(transferId)).containsExactly("DEBIT");
        assertBalance(recipientWallet, "100000.00");
        assertBalance(senderWallet, "350000.00");
    }

    // ---------------------------------------------------------------- contract of the stored messages

    @Test
    void creditAndReversalOutboxJsonFollowsContract() throws Exception {
        UUID credited = debited("150000.00");
        creditWallet.handle(credit(credited, 2, "150000.00"));
        UUID reversed = debited("1.50");
        creditWallet.handle(credit(reversed, UUID.randomUUID(), 1, "1.50"));
        reverseDebit.handle(reverse(reversed, senderId, ReverseReason.RECIPIENT_WALLET_NOT_FOUND, "1.50"));

        JsonNode walletCredited = single(credited, "WalletCredited");
        assertThat(fieldNames(walletCredited))
                .containsExactlyInAnyOrder("transferId", "walletId", "userId", "amount", "version");
        assertThat(walletCredited.get("amount").isTextual()).isTrue();
        assertThat(walletCredited.get("version").isIntegralNumber()).isTrue();

        JsonNode creditFailed = single(reversed, "CreditFailed");
        assertThat(fieldNames(creditFailed)).containsExactlyInAnyOrder("transferId", "userId", "attempt", "reason");
        assertThat(creditFailed.get("attempt").isIntegralNumber()).isTrue();

        JsonNode debitReversed = single(reversed, "DebitReversed");
        assertThat(fieldNames(debitReversed))
                .containsExactlyInAnyOrder("transferId", "walletId", "userId", "amount", "version");
        assertThat(debitReversed.get("amount").asText()).isEqualTo("1.50");

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT exchange, routing_key, payload->>'type' AS type FROM outbox_messages");
        assertThat(rows).allSatisfy(row -> {
            var destination = MessageType.fromContractName((String) row.get("type")).publishDestination();
            assertThat(row.get("exchange")).isEqualTo(destination.exchange());
            assertThat(row.get("routing_key")).isEqualTo(destination.routingKey());
        });
    }

    // ---------------------------------------------------------------- helpers

    private UUID provision(UUID userId, String opening) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, ?, 'COP', 0, now())
                """, walletId, userId, new BigDecimal(opening));
        jdbc.update("""
                INSERT INTO ledger_entries (entry_id, wallet_id, transfer_id, type, amount, balance_after, created_at)
                VALUES (?, ?, NULL, 'OPENING', ?, ?, now())
                """, UUID.randomUUID(), walletId, new BigDecimal(opening), new BigDecimal(opening));
        return walletId;
    }

    /** A transfer debited from the sender by the real stage 3 use case. */
    private UUID debited(String amount) {
        UUID transferId = UUID.randomUUID();
        debitWallet.handle(debit(transferId, amount));
        assertThat(ledgerTypes(transferId)).as("debit of " + transferId).containsExactly("DEBIT");
        return transferId;
    }

    private MessageEnvelope<DebitWallet> debit(UUID transferId, String amount) {
        return envelope(transferId, new DebitWallet(transferId, senderId, Money.parse(amount), Currency.COP));
    }

    private MessageEnvelope<CreditWallet> credit(UUID transferId, int attempt, String amount) {
        return credit(transferId, recipientId, attempt, amount);
    }

    private MessageEnvelope<CreditWallet> credit(UUID transferId, UUID userId, int attempt, String amount) {
        return envelope(transferId, new CreditWallet(transferId, userId, Money.parse(amount), Currency.COP, attempt));
    }

    private MessageEnvelope<ReverseDebit> reverse(UUID transferId, UUID userId, ReverseReason reason, String amount) {
        return envelope(transferId, new ReverseDebit(transferId, userId, Money.parse(amount), Currency.COP, reason));
    }

    private <P extends MessagePayload> MessageEnvelope<P> envelope(UUID transferId, P payload) {
        return new MessageEnvelope<>(UUID.randomUUID(), payload.type(), correlationId, transferId,
                "transfer-service", Instant.now(), payload);
    }

    private List<Throwable> runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (Runnable task : tasks) {
                Callable<Throwable> call = () -> {
                    start.await();
                    try {
                        task.run();
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                results.add(pool.submit(call));
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

    private void assertBalance(UUID walletId, String balance) {
        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE wallet_id = ?", BigDecimal.class, walletId))
                .as("balance of " + walletId).isEqualByComparingTo(balance);
    }

    /** Spec §2 LedgerEntry: balance = OPENING + CREDIT + REVERSAL - DEBIT, for every wallet. */
    private void assertLedgerMatchesBalances() {
        Integer broken = jdbc.queryForObject("""
                SELECT count(*) FROM wallets w
                WHERE w.balance <> (SELECT coalesce(sum(CASE WHEN l.type = 'DEBIT' THEN -l.amount ELSE l.amount END), 0)
                                    FROM ledger_entries l WHERE l.wallet_id = w.wallet_id)
                """, Integer.class);
        assertThat(broken).as("wallets whose balance differs from their ledger").isZero();
    }

    private void assertTotal(String total) {
        assertThat(jdbc.queryForObject("SELECT sum(balance) FROM wallets", BigDecimal.class))
                .as("money in the system").isEqualByComparingTo(total);
    }

    private String settlement(UUID transferId) {
        List<String> outcomes = jdbc.queryForList(
                "SELECT outcome FROM transfer_settlements WHERE transfer_id = ?", String.class, transferId);
        return outcomes.isEmpty() ? null : outcomes.get(0);
    }

    private List<String> ledgerTypes(UUID transferId) {
        return jdbc.queryForList("SELECT type FROM ledger_entries WHERE transfer_id = ?", String.class, transferId);
    }

    private String storedResultType(MessageEnvelope<?> command) {
        return jdbc.queryForObject("SELECT result_type FROM processed_messages WHERE message_id = ?",
                String.class, command.messageId());
    }

    private List<String> outboxTypes(UUID transferId) {
        return jdbc.queryForList("SELECT payload->>'type' FROM outbox_messages WHERE payload->>'transferId' = ?",
                String.class, transferId.toString());
    }

    private List<JsonNode> payloads(UUID transferId, String type) {
        return jdbc.queryForList("""
                        SELECT payload->'payload' FROM outbox_messages
                        WHERE payload->>'transferId' = ? AND payload->>'type' = ?
                        """, String.class, transferId.toString(), type)
                .stream().map(CreditAndReversalScenariosTest::read).toList();
    }

    private JsonNode single(UUID transferId, String type) {
        List<JsonNode> found = payloads(transferId, type);
        assertThat(found).as(type + " of " + transferId).hasSize(1);
        return found.get(0);
    }

    private List<JsonNode> audits(UUID transferId, String stage) {
        return payloads(transferId, "AttemptRecorded").stream()
                .filter(audit -> audit.get("stage").asText().equals(stage)).toList();
    }

    private void assertNothingWrittenFor(UUID transferId, UUID messageId) {
        assertThat(settlement(transferId)).isNull();
        assertThat(ledgerTypes(transferId)).isEmpty();
        assertThat(outboxTypes(transferId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages WHERE message_id = ?",
                Integer.class, messageId)).isZero();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

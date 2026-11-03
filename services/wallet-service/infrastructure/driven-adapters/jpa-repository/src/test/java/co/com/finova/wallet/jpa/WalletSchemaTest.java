package co.com.finova.wallet.jpa;

import co.com.finova.wallet.jpa.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletSchemaTest extends PostgresTestBase {

    // ---------------------------------------------------------------- schema

    @Test
    void migrationCreatesWalletTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, String.class);

        assertThat(tables).contains(
                "wallets", "ledger_entries", "transfer_settlements", "processed_messages", "outbox_messages");
    }

    @Test
    void moneyColumnsAreNumeric19_2() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name, column_name, data_type, numeric_precision, numeric_scale
                FROM information_schema.columns
                WHERE (table_name = 'wallets' AND column_name = 'balance')
                   OR (table_name = 'ledger_entries' AND column_name IN ('amount', 'balance_after'))
                """);

        assertThat(columns).hasSize(3).allSatisfy(column -> {
            assertThat(column.get("data_type")).isEqualTo("numeric");
            assertThat(column.get("numeric_precision")).isEqualTo(19);
            assertThat(column.get("numeric_scale")).isEqualTo(2);
        });
    }

    // ---------------------------------------------------------------- wallets

    @Test
    void walletBalanceCannotBeNegative() {
        assertThatThrownBy(() -> insertWallet(UUID.randomUUID(), "-0.01"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_wallets_balance_non_negative");
    }

    @Test
    void walletCurrencyMustBeCop() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, 10.00, 'USD', 0, now())
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_wallets_currency_cop");
    }

    @Test
    void oneWalletPerUser() {
        UUID userId = UUID.randomUUID();
        insertWallet(userId, "10.00");

        assertThatThrownBy(() -> insertWallet(userId, "20.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_wallets_user_id");
    }

    @Test
    void walletVersionCannotBeNegative() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, 10.00, 'COP', -1, now())
                """, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_wallets_version_non_negative");
    }

    // ---------------------------------------------------------------- ledger_entries: values

    @Test
    void ledgerAmountMustBePositive() {
        UUID walletId = insertWallet(UUID.randomUUID(), "0.00");

        assertThatThrownBy(() -> insertLedger(walletId, UUID.randomUUID(), "DEBIT", "0.00", "0.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_ledger_amount_positive");
        assertThatThrownBy(() -> insertLedger(walletId, null, "OPENING", "0.00", "0.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_ledger_amount_positive");
    }

    @Test
    void ledgerBalanceAfterCannotBeNegative() {
        UUID walletId = insertWallet(UUID.randomUUID(), "10.00");

        assertThatThrownBy(() -> insertLedger(walletId, UUID.randomUUID(), "DEBIT", "20.00", "-10.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_ledger_balance_after_non_negative");
    }

    @Test
    void ledgerRejectsUnknownType() {
        UUID walletId = insertWallet(UUID.randomUUID(), "10.00");

        assertThatThrownBy(() -> insertLedger(walletId, UUID.randomUUID(), "REFUND", "5.00", "15.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_ledger_type");
    }

    // ---------------------------------------------------------------- ledger_entries: uniqueness

    @Test
    void ledgerRejectsSecondEntryOfSameTypeForTransfer() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");
        UUID transferId = UUID.randomUUID();
        insertLedger(walletId, transferId, "DEBIT", "10.00", "90.00");

        assertThatThrownBy(() -> insertLedger(walletId, transferId, "DEBIT", "10.00", "80.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_ledger_transfer_type");
    }

    @Test
    void ledgerAllowsDebitAndReversalOfSameTransfer() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");
        UUID transferId = UUID.randomUUID();
        insertLedger(walletId, transferId, "DEBIT", "10.00", "90.00");

        assertThatCode(() -> insertLedger(walletId, transferId, "REVERSAL", "10.00", "100.00"))
                .doesNotThrowAnyException();
    }

    @Test
    void ledgerOpeningRequiresNullTransferId() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");

        assertThatThrownBy(() -> insertLedger(walletId, UUID.randomUUID(), "OPENING", "100.00", "100.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_ledger_transfer_id_null_only_for_opening");
    }

    @Test
    void ledgerMovementRequiresTransferId() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");

        for (String type : List.of("DEBIT", "CREDIT", "REVERSAL")) {
            assertThatThrownBy(() -> insertLedger(walletId, null, type, "10.00", "90.00"))
                    .as(type)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_ledger_transfer_id_null_only_for_opening");
        }
    }

    @Test
    void ledgerAllowsOnlyOneOpeningPerWallet() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");
        insertLedger(walletId, null, "OPENING", "100.00", "100.00");

        assertThatThrownBy(() -> insertLedger(walletId, null, "OPENING", "50.00", "50.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_ledger_one_opening_per_wallet");
    }

    @Test
    void ledgerRequiresExistingWallet() {
        assertThatThrownBy(() -> insertLedger(UUID.randomUUID(), UUID.randomUUID(), "CREDIT", "10.00", "10.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_ledger_wallet");
    }

    // ---------------------------------------------------------------- ledger_entries: append-only

    @Test
    void ledgerRejectsUpdate() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");
        insertLedger(walletId, null, "OPENING", "100.00", "100.00");

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 999.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ledger_entries is append-only");
    }

    @Test
    void ledgerRejectsDelete() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");
        insertLedger(walletId, null, "OPENING", "100.00", "100.00");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ledger_entries is append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isEqualTo(1);
    }

    @Test
    void ledgerRejectsTruncate() {
        UUID walletId = insertWallet(UUID.randomUUID(), "100.00");
        insertLedger(walletId, null, "OPENING", "100.00", "100.00");

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ledger_entries is append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE wallets CASCADE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ledger_entries is append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- transfer_settlements

    @Test
    void settlementAllowsOnlyOneOutcomePerTransfer() {
        UUID transferId = UUID.randomUUID();
        insertSettlement(transferId, "CREDITED");

        assertThatThrownBy(() -> insertSettlement(transferId, "REVERSED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pk_transfer_settlements");
    }

    @Test
    void settlementOutcomeMustBeCreditedOrReversed() {
        assertThatThrownBy(() -> insertSettlement(UUID.randomUUID(), "PENDING"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_settlements_outcome");
    }

    // ---------------------------------------------------------------- processed_messages

    @Test
    void processedMessageIsUniquePerMessageAndConsumer() {
        UUID messageId = UUID.randomUUID();
        insertProcessed(messageId, "wallet-commands", "WalletDebited");

        assertThatThrownBy(() -> insertProcessed(messageId, "wallet-commands", "WalletDebited"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pk_processed_messages");
    }

    @Test
    void processedMessageAllowsSameMessageForAnotherConsumer() {
        UUID messageId = UUID.randomUUID();
        insertProcessed(messageId, "wallet-commands", "WalletDebited");

        assertThatCode(() -> insertProcessed(messageId, "wallet-balance-projection", null))
                .doesNotThrowAnyException();
    }

    @Test
    void processedMessageResultTypeMustBeAWalletResult() {
        assertThatThrownBy(() -> insertProcessed(UUID.randomUUID(), "wallet-commands", "TransferCompleted"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_processed_result_type");
    }

    // ---------------------------------------------------------------- outbox_messages

    @Test
    void outboxStoresPayloadAsJsonbAndStartsUnpublished() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_messages (id, exchange, routing_key, payload, created_at)
                VALUES (?, 'wallet.events', 'wallet.debited', ?::jsonb, now())
                """, id, "{\"messageId\":\"" + id + "\",\"type\":\"WalletDebited\",\"payload\":{\"amount\":\"150000.00\"}}");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT payload ->> 'type' AS type, payload -> 'payload' ->> 'amount' AS amount, published_at
                FROM outbox_messages WHERE id = ?
                """, id);

        assertThat(row.get("type")).isEqualTo("WalletDebited");
        assertThat(row.get("amount")).isEqualTo("150000.00");
        assertThat(row.get("published_at")).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private UUID insertWallet(UUID userId, String balance) {
        UUID walletId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, ?, 'COP', 0, now())
                """, walletId, userId, new BigDecimal(balance));
        return walletId;
    }

    private void insertLedger(UUID walletId, UUID transferId, String type, String amount, String balanceAfter) {
        jdbc.update("""
                INSERT INTO ledger_entries (entry_id, wallet_id, transfer_id, type, amount, balance_after, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), walletId, transferId, type, new BigDecimal(amount), new BigDecimal(balanceAfter));
    }

    private void insertSettlement(UUID transferId, String outcome) {
        jdbc.update("INSERT INTO transfer_settlements (transfer_id, outcome, settled_at) VALUES (?, ?, now())",
                transferId, outcome);
    }

    private void insertProcessed(UUID messageId, String consumer, String resultType) {
        jdbc.update("""
                INSERT INTO processed_messages (message_id, consumer, processed_at, result_type, result_payload)
                VALUES (?, ?, now(), ?, NULL)
                """, messageId, consumer, resultType);
    }
}

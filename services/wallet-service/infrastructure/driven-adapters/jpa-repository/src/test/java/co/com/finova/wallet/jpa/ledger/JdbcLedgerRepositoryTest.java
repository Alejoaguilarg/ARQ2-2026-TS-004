package co.com.finova.wallet.jpa.ledger;

import co.com.finova.wallet.jpa.support.PostgresTestBase;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.ledger.DuplicateLedgerEntryException;
import co.com.finova.wallet.model.ledger.LedgerEntry;
import co.com.finova.wallet.model.ledger.LedgerEntryType;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcLedgerRepositoryTest extends PostgresTestBase {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private final JdbcLedgerRepository repository = new JdbcLedgerRepository(JdbcClient.create(jdbc));

    private final UUID walletId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private Wallet walletAfter;

    @BeforeEach
    void wallet() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, 350000.00, 'COP', 1, now())
                """, walletId, userId);
        walletAfter = new Wallet(walletId, userId, Money.parse("350000.00"), Currency.COP, 1, NOW);
    }

    @Test
    void appendInsertsEveryColumn() {
        UUID entryId = UUID.randomUUID();

        repository.append(LedgerEntry.debit(entryId, transferId, Money.parse("150000.00"), walletAfter, NOW));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ledger_entries WHERE entry_id = ?", entryId);
        assertThat(row.get("wallet_id")).isEqualTo(walletId);
        assertThat(row.get("transfer_id")).isEqualTo(transferId);
        assertThat(row.get("type")).isEqualTo("DEBIT");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("150000.00");
        assertThat((BigDecimal) row.get("balance_after")).isEqualByComparingTo("350000.00");
        assertThat(jdbc.queryForObject("SELECT created_at FROM ledger_entries WHERE entry_id = ?",
                OffsetDateTime.class, entryId).toInstant()).isEqualTo(NOW);
    }

    @Test
    void secondEntryOfSameTypeForTransferBecomesDuplicateLedgerEntry() {
        repository.append(LedgerEntry.debit(UUID.randomUUID(), transferId, Money.parse("10.00"), walletAfter, NOW));

        assertThatThrownBy(() -> repository.append(
                LedgerEntry.debit(UUID.randomUUID(), transferId, Money.parse("10.00"), walletAfter, NOW)))
                .isInstanceOfSatisfying(DuplicateLedgerEntryException.class, e -> {
                    assertThat(e.transferId()).isEqualTo(transferId);
                    assertThat(e.type()).isEqualTo(LedgerEntryType.DEBIT);
                });
    }

    @Test
    void findDebitReturnsTheDebitOfTheTransfer() {
        UUID entryId = UUID.randomUUID();
        repository.append(LedgerEntry.debit(entryId, transferId, Money.parse("150000.00"), walletAfter, NOW));
        repository.append(LedgerEntry.reversal(UUID.randomUUID(), transferId, Money.parse("150000.00"),
                walletAfter, NOW));
        repository.append(LedgerEntry.debit(UUID.randomUUID(), UUID.randomUUID(), Money.parse("1.00"),
                walletAfter, NOW));

        assertThat(repository.findDebit(transferId)).contains(LedgerEntry.debit(entryId, transferId,
                Money.parse("150000.00"), walletAfter, NOW));
    }

    @Test
    void findDebitIsEmptyWithoutDebit() {
        repository.append(LedgerEntry.credit(UUID.randomUUID(), transferId, Money.parse("10.00"), walletAfter, NOW));

        assertThat(repository.findDebit(transferId)).isEmpty();
        assertThat(repository.findDebit(UUID.randomUUID())).isEmpty();
    }

    @Test
    void otherDuplicateKeysAreNotTranslated() {
        UUID entryId = UUID.randomUUID();
        repository.append(LedgerEntry.debit(entryId, transferId, Money.parse("10.00"), walletAfter, NOW));

        assertThatThrownBy(() -> repository.append(
                LedgerEntry.reversal(entryId, transferId, Money.parse("10.00"), walletAfter, NOW)))
                .isInstanceOf(DuplicateKeyException.class);
    }
}

package co.com.finova.wallet.jpa.wallet;

import co.com.finova.wallet.jpa.support.PostgresTestBase;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWalletRepositoryTest extends PostgresTestBase {

    private final JdbcWalletRepository repository = new JdbcWalletRepository(JdbcClient.create(jdbc));

    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    /** The instant the use case read from its Clock; PostgreSQL keeps microseconds. */
    private final Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Test
    void findByUserIdMapsEveryColumn() {
        insertWallet("500000.00", 3);

        Wallet wallet = repository.findByUserId(userId).orElseThrow();

        assertThat(wallet.walletId()).isEqualTo(walletId);
        assertThat(wallet.userId()).isEqualTo(userId);
        assertThat(wallet.balance()).isEqualTo(Money.parse("500000.00"));
        assertThat(wallet.currency()).isEqualTo(Currency.COP);
        assertThat(wallet.version()).isEqualTo(3);
        assertThat(wallet.updatedAt()).isNotNull();
    }

    @Test
    void findByUserIdIsEmptyWithoutWallet() {
        assertThat(repository.findByUserId(userId)).isEmpty();
    }

    @Test
    void debitSubtractsAmountAndIncrementsVersion() {
        insertWallet("500000.00", 3);

        Wallet after = repository.debitIfSufficient(userId, Money.parse("150000.00"), updatedAt).orElseThrow();

        assertThat(after.walletId()).isEqualTo(walletId);
        assertThat(after.balance()).isEqualTo(Money.parse("350000.00"));
        assertThat(after.version()).isEqualTo(4);
        assertRow("350000.00", 4, true);
    }

    @Test
    void debitStoresTheGivenUpdatedAt() {
        insertWallet("500000.00", 0);

        Wallet after = repository.debitIfSufficient(userId, Money.parse("1.00"), updatedAt).orElseThrow();

        assertThat(after.updatedAt()).isEqualTo(updatedAt);
        assertThat(storedUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void debitCanTakeTheWholeBalance() {
        insertWallet("80000.00", 0);

        Wallet after = repository.debitIfSufficient(userId, Money.parse("80000.00"), updatedAt).orElseThrow();

        assertThat(after.balance()).isEqualTo(Money.parse("0.00"));
        assertRow("0.00", 1, true);
    }

    @Test
    void debitUpdatesNothingWhenFundsAreInsufficient() {
        insertWallet("50000.00", 2);

        assertThat(repository.debitIfSufficient(userId, Money.parse("50000.01"), updatedAt)).isEmpty();

        assertRow("50000.00", 2, false);
    }

    @Test
    void debitUpdatesNothingWithoutWallet() {
        assertThat(repository.debitIfSufficient(userId, Money.parse("1.00"), updatedAt)).isEmpty();
    }

    @Test
    void debitTouchesOnlyTheWalletOfThatUser() {
        insertWallet("100.00", 0);
        UUID otherUser = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, 100.00, 'COP', 0, now())
                """, UUID.randomUUID(), otherUser);

        repository.debitIfSufficient(userId, Money.parse("40.00"), updatedAt);

        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", BigDecimal.class,
                otherUser)).isEqualByComparingTo("100.00");
    }

    @Test
    void increaseBalanceAddsAmountAndIncrementsVersion() {
        insertWallet("100000.00", 5);

        Wallet after = repository.increaseBalance(walletId, Money.parse("150000.00"), updatedAt);

        assertThat(after.walletId()).isEqualTo(walletId);
        assertThat(after.userId()).isEqualTo(userId);
        assertThat(after.balance()).isEqualTo(Money.parse("250000.00"));
        assertThat(after.version()).isEqualTo(6);
        assertRow("250000.00", 6, true);
    }

    @Test
    void increaseBalanceStoresTheGivenUpdatedAt() {
        insertWallet("100.00", 0);

        Wallet after = repository.increaseBalance(walletId, Money.parse("1.00"), updatedAt);

        assertThat(after.updatedAt()).isEqualTo(updatedAt);
        assertThat(storedUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void increaseBalanceFailsForUnknownWallet() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> repository.increaseBalance(UUID.randomUUID(), Money.parse("1.00"), updatedAt))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- balance cache rebuild (decision S6)

    @Test
    void findPageAfterWalksEveryWalletInWalletIdOrder() {
        // PostgreSQL orders uuid byte by byte, which is the order of their lowercase text.
        List<UUID> ids = Stream.generate(UUID::randomUUID).limit(3).toList();
        ids.forEach(id -> insertWallet(id, UUID.randomUUID()));
        List<UUID> expected = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();

        List<Wallet> first = repository.findPageAfter(null, 2);
        List<Wallet> second = repository.findPageAfter(first.get(1).walletId(), 2);

        assertThat(first).extracting(Wallet::walletId).containsExactly(expected.get(0), expected.get(1));
        assertThat(second).extracting(Wallet::walletId).containsExactly(expected.get(2));
        assertThat(repository.findPageAfter(expected.get(2), 2)).isEmpty();
    }

    @Test
    void findPageAfterMapsEveryColumn() {
        insertWallet("500000.00", 3);

        Wallet wallet = repository.findPageAfter(null, 10).get(0);

        assertThat(wallet).isEqualTo(repository.findByUserId(userId).orElseThrow());
    }

    @Test
    void findPageAfterIsEmptyWithoutWallets() {
        assertThat(repository.findPageAfter(null, 10)).isEmpty();
    }

    private void insertWallet(UUID id, UUID owner) {
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, 100.00, 'COP', 0, now())
                """, id, owner);
    }

    private Instant storedUpdatedAt() {
        return jdbc.queryForObject("SELECT updated_at FROM wallets WHERE wallet_id = ?", OffsetDateTime.class,
                walletId).toInstant();
    }

    private void insertWallet(String balance, long version) {
        jdbc.update("""
                INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at)
                VALUES (?, ?, ?, 'COP', ?, now() - interval '1 day')
                """, walletId, userId, new BigDecimal(balance), version);
    }

    private void assertRow(String balance, long version, boolean touched) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT balance, version, updated_at > now() - interval '1 hour' AS touched FROM wallets "
                        + "WHERE wallet_id = ?", walletId);
        assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo(balance);
        assertThat(row.get("version")).isEqualTo(version);
        assertThat(row.get("touched")).as("updated_at changed").isEqualTo(touched);
    }
}

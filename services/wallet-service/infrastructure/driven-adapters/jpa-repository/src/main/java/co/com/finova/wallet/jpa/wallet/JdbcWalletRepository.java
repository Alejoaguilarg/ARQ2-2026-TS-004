package co.com.finova.wallet.jpa.wallet;

import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import co.com.finova.wallet.model.wallet.gateways.WalletRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private static final String COLUMNS = "wallet_id, user_id, balance, currency, version, updated_at";

    private final JdbcClient jdbc;

    public JdbcWalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Wallet> findByUserId(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM wallets WHERE user_id = :userId")
                .param("userId", userId)
                .query(JdbcWalletRepository::toWallet)
                .optional();
    }

    /**
     * Spec §4.7: one conditional UPDATE. PostgreSQL locks the row, so a concurrent debit waits and then
     * re-checks balance >= :amount against the committed balance; the balance never goes below zero.
     */
    @Override
    public Optional<Wallet> debitIfSufficient(UUID userId, Money amount) {
        return jdbc.sql("""
                        UPDATE wallets
                        SET balance = balance - :amount, version = version + 1, updated_at = now()
                        WHERE user_id = :userId AND balance >= :amount
                        RETURNING\s""" + COLUMNS)
                .param("amount", amount.toBigDecimal())
                .param("userId", userId)
                .query(JdbcWalletRepository::toWallet)
                .optional();
    }

    /** Credit and reversal: one UPDATE that adds the amount and increments the version. */
    @Override
    public Wallet increaseBalance(UUID walletId, Money amount) {
        return jdbc.sql("""
                        UPDATE wallets
                        SET balance = balance + :amount, version = version + 1, updated_at = now()
                        WHERE wallet_id = :walletId
                        RETURNING\s""" + COLUMNS)
                .param("amount", amount.toBigDecimal())
                .param("walletId", walletId)
                .query(JdbcWalletRepository::toWallet)
                .optional()
                .orElseThrow(() -> new IllegalStateException("wallet " + walletId + " does not exist"));
    }

    private static Wallet toWallet(ResultSet rs, int rowNum) throws SQLException {
        return new Wallet(
                rs.getObject("wallet_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Money.of(rs.getBigDecimal("balance")),
                Currency.fromCode(rs.getString("currency")),
                rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}

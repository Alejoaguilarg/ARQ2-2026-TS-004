package co.com.finova.wallet.model.wallet;

import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static co.com.finova.wallet.model.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Money balance = Money.parse("500000.00");

    @Test
    void keepsAllFields_s2() {
        Wallet wallet = new Wallet(walletId, userId, balance, Currency.COP, 0, NOW);

        assertThat(wallet.walletId()).isEqualTo(walletId);
        assertThat(wallet.userId()).isEqualTo(userId);
        assertThat(wallet.balance()).isEqualTo(balance);
        assertThat(wallet.currency()).isEqualTo(Currency.COP);
        assertThat(wallet.version()).isZero();
        assertThat(wallet.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void acceptsZeroBalance_s2() {
        assertThat(new Wallet(walletId, userId, Money.ZERO, Currency.COP, 3, NOW).balance()).isEqualTo(Money.ZERO);
    }

    @Test
    void rejectsNegativeVersion_s2() {
        assertThatThrownBy(() -> new Wallet(walletId, userId, balance, Currency.COP, -1, NOW))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsMissingRequiredFields_s2() {
        assertThatThrownBy(() -> new Wallet(null, userId, balance, Currency.COP, 0, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletId");
        assertThatThrownBy(() -> new Wallet(walletId, null, balance, Currency.COP, 0, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new Wallet(walletId, userId, null, Currency.COP, 0, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("balance");
        assertThatThrownBy(() -> new Wallet(walletId, userId, balance, null, 0, NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> new Wallet(walletId, userId, balance, Currency.COP, 0, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("updatedAt");
    }
}

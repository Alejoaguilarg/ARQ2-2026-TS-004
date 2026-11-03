package co.com.finova.wallet.model.balance;

import co.com.finova.wallet.model.Fixtures;
import co.com.finova.wallet.model.common.ContractViolationException;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.message.MessageEnvelope;
import co.com.finova.wallet.model.message.payload.BalanceChanged;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.Wallet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceSnapshotTest {

    private final UUID walletId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Money available = Money.parse("850000.00");

    @Test
    void keepsEveryField() {
        BalanceSnapshot snapshot = new BalanceSnapshot(walletId, userId, available, Currency.COP, 42, Fixtures.NOW);

        assertThat(snapshot.walletId()).isEqualTo(walletId);
        assertThat(snapshot.userId()).isEqualTo(userId);
        assertThat(snapshot.available()).isEqualTo(available);
        assertThat(snapshot.currency()).isEqualTo(Currency.COP);
        assertThat(snapshot.version()).isEqualTo(42);
        assertThat(snapshot.asOf()).isEqualTo(Fixtures.NOW);
    }

    @Test
    void acceptsZeroBalanceAndVersionZero() {
        BalanceSnapshot snapshot = new BalanceSnapshot(walletId, userId, Money.ZERO, Currency.COP, 0, Fixtures.NOW);

        assertThat(snapshot.available()).isEqualTo(Money.ZERO);
        assertThat(snapshot.version()).isZero();
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> new BalanceSnapshot(null, userId, available, Currency.COP, 1, Fixtures.NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("walletId");
        assertThatThrownBy(() -> new BalanceSnapshot(walletId, null, available, Currency.COP, 1, Fixtures.NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("userId");
        assertThatThrownBy(() -> new BalanceSnapshot(walletId, userId, null, Currency.COP, 1, Fixtures.NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("available");
        assertThatThrownBy(() -> new BalanceSnapshot(walletId, userId, available, null, 1, Fixtures.NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> new BalanceSnapshot(walletId, userId, available, Currency.COP, 1, null))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("asOf");
    }

    @Test
    void rejectsNegativeVersion() {
        assertThatThrownBy(() -> new BalanceSnapshot(walletId, userId, available, Currency.COP, -1, Fixtures.NOW))
                .isInstanceOf(ContractViolationException.class).hasMessageContaining("version");
    }

    @Test
    void fromWalletUsesUpdatedAtAsAsOf() {
        Instant updatedAt = Instant.parse("2026-11-02T14:59:00.123456Z");
        Wallet wallet = new Wallet(walletId, userId, available, Currency.COP, 7, updatedAt);

        assertThat(BalanceSnapshot.fromWallet(wallet))
                .isEqualTo(new BalanceSnapshot(walletId, userId, available, Currency.COP, 7, updatedAt));
    }

    @Test
    void fromWalletRejectsMissingWallet() {
        assertThatThrownBy(() -> BalanceSnapshot.fromWallet(null)).isInstanceOf(ContractViolationException.class);
    }

    @Test
    void fromEventUsesOccurredAtAsAsOfAndCop() {
        Instant occurredAt = Instant.parse("2026-11-02T14:59:00.123456Z");
        MessageEnvelope<BalanceChanged> event = MessageEnvelope.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), occurredAt, new BalanceChanged(walletId, userId, available, 9));

        assertThat(BalanceSnapshot.fromEvent(event))
                .isEqualTo(new BalanceSnapshot(walletId, userId, available, Currency.COP, 9, occurredAt));
    }

    @Test
    void fromEventRejectsMissingEvent() {
        assertThatThrownBy(() -> BalanceSnapshot.fromEvent(null)).isInstanceOf(ContractViolationException.class);
    }
}

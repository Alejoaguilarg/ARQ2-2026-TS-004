package co.com.finova.wallet.jpa;

import co.com.finova.wallet.model.balance.Balance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class WalletRepositoryAdapterTest {

    @Autowired
    private WalletJpaRepository repository;

    @Test
    void mapsTheWalletOfAUserToItsBalance() {
        UUID walletId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-10-19T15:00:00.120Z");
        repository.save(new WalletEntity(walletId, userId, new BigDecimal("850000.00"), "COP", 42, updatedAt));

        assertThat(new WalletRepositoryAdapter(repository).findBalanceByUserId(userId)).contains(
                new Balance(walletId, userId, new BigDecimal("850000.00"), "COP", 42, updatedAt));
    }

    @Test
    void unknownUserHasNoWallet() {
        assertThat(new WalletRepositoryAdapter(repository).findBalanceByUserId(UUID.randomUUID())).isEmpty();
    }
}

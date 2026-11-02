package co.com.finova.wallet.usecase.balance;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.WalletNotFoundException;
import co.com.finova.wallet.model.balance.gateways.BalanceCache;
import co.com.finova.wallet.model.balance.gateways.WalletRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetBalanceUseCaseTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Balance BALANCE =
            new Balance(UUID.randomUUID(), USER, new BigDecimal("850000.00"), "COP", 42, Instant.now());

    private final BalanceCache cache = mock(BalanceCache.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final GetBalanceUseCase useCase = new GetBalanceUseCase(cache, wallets);

    @Test
    void f21_servesFromCacheWithoutTouchingTheDatabase() {
        when(cache.find(USER)).thenReturn(Optional.of(BALANCE));

        assertThat(useCase.execute(USER)).isEqualTo(BALANCE);
        verifyNoInteractions(wallets);
    }

    @Test
    void f22_cacheMissReadsDatabaseAndWarmsCache() {
        when(cache.find(USER)).thenReturn(Optional.empty());
        when(wallets.findBalanceByUserId(USER)).thenReturn(Optional.of(BALANCE));

        assertThat(useCase.execute(USER)).isEqualTo(BALANCE);
        verify(cache).putIfNewer(BALANCE);
    }

    @Test
    void f26_cacheReadFailureFallsBackToDatabaseWithoutWarmUp() {
        when(cache.find(USER)).thenThrow(new IllegalStateException("redis down"));
        when(wallets.findBalanceByUserId(USER)).thenReturn(Optional.of(BALANCE));

        assertThat(useCase.execute(USER)).isEqualTo(BALANCE);
        verify(cache, never()).putIfNewer(any());
    }

    @Test
    void warmUpFailureStillReturnsTheBalance() {
        when(cache.find(USER)).thenReturn(Optional.empty());
        when(wallets.findBalanceByUserId(USER)).thenReturn(Optional.of(BALANCE));
        when(cache.putIfNewer(BALANCE)).thenThrow(new IllegalStateException("redis down"));

        assertThat(useCase.execute(USER)).isEqualTo(BALANCE);
    }

    @Test
    void unknownUserIsWalletNotFound() {
        when(cache.find(USER)).thenReturn(Optional.empty());
        when(wallets.findBalanceByUserId(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(USER)).isInstanceOf(WalletNotFoundException.class);
    }
}

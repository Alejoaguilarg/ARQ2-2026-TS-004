package co.com.finova.wallet.usecase.balance;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.gateways.BalanceCache;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateBalanceProjectionUseCaseTest {

    private static final Balance BALANCE =
            new Balance(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "COP", 7, Instant.now());

    private final BalanceCache cache = mock(BalanceCache.class);
    private final UpdateBalanceProjectionUseCase useCase = new UpdateBalanceProjectionUseCase(cache);

    @Test
    void reportsWhetherTheEventWasApplied() {
        when(cache.putIfNewer(BALANCE)).thenReturn(false);

        assertThat(useCase.execute(BALANCE)).isFalse();
    }

    @Test
    void cacheFailurePropagatesSoTheMessageIsRetried() {
        when(cache.putIfNewer(BALANCE)).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> useCase.execute(BALANCE)).isInstanceOf(IllegalStateException.class);
    }
}

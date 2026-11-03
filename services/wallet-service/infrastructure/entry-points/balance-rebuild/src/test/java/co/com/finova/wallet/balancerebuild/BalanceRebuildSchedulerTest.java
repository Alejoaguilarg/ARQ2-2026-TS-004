package co.com.finova.wallet.balancerebuild;

import co.com.finova.wallet.usecase.rebuildbalancecache.RebuildBalanceCacheUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BalanceRebuildSchedulerTest {

    private final RebuildBalanceCacheUseCase useCase = mock(RebuildBalanceCacheUseCase.class);
    private final BalanceRebuildScheduler scheduler = new BalanceRebuildScheduler(useCase, 250);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void runRebuildsWithTheConfiguredPageSize() {
        when(useCase.rebuild(anyInt())).thenReturn(new RebuildBalanceCacheUseCase.Result(2, 1, true));

        scheduler.rebuild();

        verify(useCase).rebuild(250);
    }

    @Test
    void everyRunHasItsOwnCorrelationIdInTheMdc() {
        AtomicReference<String> seen = new AtomicReference<>();
        when(useCase.rebuild(anyInt())).thenAnswer(invocation -> {
            seen.set(MDC.get("correlationId"));
            return new RebuildBalanceCacheUseCase.Result(0, 0, true);
        });

        scheduler.rebuild();

        assertThat(UUID.fromString(seen.get())).isNotNull();
        assertThat(MDC.get("correlationId")).as("removed after the run").isNull();
    }

    @Test
    void incompleteRunIsNotAnError() {
        when(useCase.rebuild(anyInt())).thenReturn(new RebuildBalanceCacheUseCase.Result(0, 0, false));

        assertThatCode(scheduler::rebuild).doesNotThrowAnyException();
    }

    @Test
    void failingRunIsSwallowedSoTheNextOneRetries() {
        when(useCase.rebuild(anyInt())).thenThrow(new IllegalStateException("wallet_db down"));

        assertThatCode(scheduler::rebuild).doesNotThrowAnyException();
        assertThat(MDC.get("correlationId")).isNull();
    }
}

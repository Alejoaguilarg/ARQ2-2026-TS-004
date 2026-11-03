package co.com.finova.transfer.sagasweeper;

import co.com.finova.transfer.usecase.sweeptimeouts.SweepTimeoutsUseCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SagaSweepSchedulerTest {

    private final SweepTimeoutsUseCase sweeper = mock(SweepTimeoutsUseCase.class);
    private final SagaSweepScheduler scheduler = new SagaSweepScheduler(sweeper, 50);

    @Test
    void eachRunSweepsOneBatch() {
        scheduler.sweep();

        verify(sweeper).sweep(50);
    }

    @Test
    void aFailedRunIsLoggedAndTheNextOneTriesAgain() {
        when(sweeper.sweep(50)).thenThrow(new IllegalStateException("transfer_db down"));

        assertThatCode(scheduler::sweep).doesNotThrowAnyException();
    }
}

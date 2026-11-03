package co.com.finova.gateway.usecase.idempotency;

import co.com.finova.gateway.model.idempotency.IdempotencyRecord;
import co.com.finova.gateway.model.idempotency.gateways.IdempotencyStore;
import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase.Decision;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyUseCaseTest {

    private static final String USER = "u-1";
    private static final String KEY = "k-1";

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyUseCase useCase = new IdempotencyUseCase(store);

    @Test
    void f32_newKeyProceeds() {
        when(store.createIfAbsent(USER, KEY, IdempotencyRecord.inProgress("h"))).thenReturn(true);

        assertThat(useCase.begin(USER, KEY, "h")).isInstanceOf(Decision.Proceed.class);
    }

    @Test
    void f33_sameKeyWhileInProgressIsAConflict() {
        when(store.createIfAbsent(any(), any(), any())).thenReturn(false);
        when(store.find(USER, KEY)).thenReturn(Optional.of(IdempotencyRecord.inProgress("h")));

        assertThat(useCase.begin(USER, KEY, "h")).isInstanceOf(Decision.InProgress.class);
    }

    @Test
    void f34_sameKeyAfterCompletionReplaysTheStoredResponse() {
        when(store.createIfAbsent(any(), any(), any())).thenReturn(false);
        when(store.find(USER, KEY)).thenReturn(Optional.of(IdempotencyRecord.completed("h", 202, "{\"transferId\":\"t\"}")));

        assertThat(useCase.begin(USER, KEY, "h")).isEqualTo(new Decision.Replay(202, "{\"transferId\":\"t\"}"));
    }

    @Test
    void f35_sameKeyWithDifferentBodyIsReusedWhateverTheState() {
        when(store.createIfAbsent(any(), any(), any())).thenReturn(false);
        when(store.find(USER, KEY))
                .thenReturn(Optional.of(IdempotencyRecord.inProgress("other")))
                .thenReturn(Optional.of(IdempotencyRecord.completed("other", 202, "{}")));

        assertThat(useCase.begin(USER, KEY, "h")).isInstanceOf(Decision.KeyReused.class);
        assertThat(useCase.begin(USER, KEY, "h")).isInstanceOf(Decision.KeyReused.class);
    }

    @Test
    void keyThatExpiresBetweenCreateAndReadIsCreatedOnTheSecondAttempt() {
        when(store.createIfAbsent(any(), any(), any())).thenReturn(false).thenReturn(true);
        when(store.find(USER, KEY)).thenReturn(Optional.empty());

        assertThat(useCase.begin(USER, KEY, "h")).isInstanceOf(Decision.Proceed.class);
    }

    @Test
    void f32_upstreamAnswerIsStoredAsCompleted() {
        useCase.finish(USER, KEY, "h", 202, "{}");

        verify(store).save(USER, KEY, IdempotencyRecord.completed("h", 202, "{}"));
    }

    @Test
    void upstream4xxIsAlsoStoredForReplay() {
        useCase.finish(USER, KEY, "h", 422, "{}");

        verify(store).save(USER, KEY, IdempotencyRecord.completed("h", 422, "{}"));
    }

    @Test
    void f38_upstream5xxDeletesTheKeySoTheClientCanRetry() {
        useCase.finish(USER, KEY, "h", 503, "{}");

        verify(store).delete(USER, KEY);
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void f38_noUpstreamAnswerDeletesTheKey() {
        useCase.abort(USER, KEY);

        verify(store).delete(USER, KEY);
    }
}

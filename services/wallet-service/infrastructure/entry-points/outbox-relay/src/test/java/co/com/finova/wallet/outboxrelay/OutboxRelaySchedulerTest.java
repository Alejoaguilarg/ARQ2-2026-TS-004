package co.com.finova.wallet.outboxrelay;

import co.com.finova.wallet.usecase.relayoutbox.RelayOutboxUseCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelaySchedulerTest {

    private final RelayOutboxUseCase relay = mock(RelayOutboxUseCase.class);

    @Test
    void relaysOneBatchOfTheConfiguredSize() {
        new OutboxRelayScheduler(relay, 100).relayPending();

        verify(relay).relay(100);
    }

    @Test
    void keepsRunningWhenARunFails() {
        when(relay.relay(100)).thenThrow(new IllegalStateException("wallet_db unavailable"));

        assertThatCode(() -> new OutboxRelayScheduler(relay, 100).relayPending()).doesNotThrowAnyException();
    }
}

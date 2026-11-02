package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.gateways.SessionStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionUseCaseTest {

    private final SessionStore sessions = mock(SessionStore.class);
    private final SessionUseCase useCase = new SessionUseCase(sessions);

    @Test
    void f14_revokedTokenOrSessionIsReported() {
        when(sessions.isRevoked("jti-1", "sid-1")).thenReturn(true);

        assertThat(useCase.isRevoked("jti-1", "sid-1")).isTrue();
    }

    @Test
    void unusedFinancialSessionIsClaimed() {
        when(sessions.claimFinancialSession("fs-1", "k1")).thenReturn(Optional.empty());

        assertThat(useCase.claimFinancialSession("fs-1", "k1")).isTrue();
    }

    @Test
    void retryOfTheSameTransferIntentMayReuseTheFinancialSession() {
        when(sessions.claimFinancialSession("fs-1", "k1")).thenReturn(Optional.of("k1"));

        assertThat(useCase.claimFinancialSession("fs-1", "k1")).isTrue();
    }

    @Test
    void financialSessionUsedByAnotherTransferIsRejected() {
        when(sessions.claimFinancialSession("fs-1", "k2")).thenReturn(Optional.of("k1"));

        assertThat(useCase.claimFinancialSession("fs-1", "k2")).isFalse();
    }
}

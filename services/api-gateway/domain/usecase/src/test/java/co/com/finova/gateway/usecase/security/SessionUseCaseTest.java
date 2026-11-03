package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.FinancialSessionToken;
import co.com.finova.gateway.model.security.InvalidTokenException;
import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.model.security.gateways.SessionStore;
import co.com.finova.gateway.model.security.gateways.TokenVerifier;
import co.com.finova.gateway.usecase.security.SessionUseCase.Authentication;
import co.com.finova.gateway.usecase.security.SessionUseCase.FinancialSessionCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionUseCaseTest {

    private static final AccessToken USER = new AccessToken("jti-1", "u-1", "d-1", "sid-1", List.of("USER"));

    private final TokenVerifier tokens = mock(TokenVerifier.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final SessionUseCase useCase = new SessionUseCase(tokens, sessions);

    @Test
    void validTokenThatIsNotRevokedIsAuthenticated() {
        when(tokens.verifyAccessToken("at")).thenReturn(USER);

        assertThat(useCase.authenticate("at")).isEqualTo(new Authentication.Authenticated(USER));
    }

    @Test
    void invalidTokenIsUnauthorizedWithoutTouchingTheStore() {
        when(tokens.verifyAccessToken("at")).thenThrow(new InvalidTokenException("bad signature", false));

        assertThat(useCase.authenticate("at")).isInstanceOf(Authentication.Unauthorized.class);
        verify(sessions, never()).isRevoked(any(), any());
    }

    @Test
    void f14_revokedTokenOrSessionIsRejected() {
        when(tokens.verifyAccessToken("at")).thenReturn(USER);
        when(sessions.isRevoked("jti-1", "sid-1")).thenReturn(true);

        assertThat(useCase.authenticate("at")).isInstanceOf(Authentication.Revoked.class);
    }

    @Test
    void f112_storeDownPropagatesSoTheGatewayFailsClosed() {
        when(tokens.verifyAccessToken("at")).thenReturn(USER);
        when(sessions.isRevoked("jti-1", "sid-1")).thenThrow(new SecurityStoreUnavailableException(new RuntimeException()));

        assertThatThrownBy(() -> useCase.authenticate("at")).isInstanceOf(SecurityStoreUnavailableException.class);
    }

    @Test
    void unusedFinancialSessionIsAccepted() {
        session(new FinancialSessionToken("fs-1", "u-1", "d-1", "transfer:create"));
        when(sessions.claimFinancialSession("fs-1", "k1")).thenReturn(Optional.empty());

        assertThat(useCase.checkFinancialSession("fs", USER, "k1")).isEqualTo(FinancialSessionCheck.ACCEPTED);
    }

    @Test
    void f111_retryOfTheSameTransferIntentMayReuseTheFinancialSession() {
        session(new FinancialSessionToken("fs-1", "u-1", "d-1", "transfer:create"));
        when(sessions.claimFinancialSession("fs-1", "k1")).thenReturn(Optional.of("k1"));

        assertThat(useCase.checkFinancialSession("fs", USER, "k1")).isEqualTo(FinancialSessionCheck.ACCEPTED);
    }

    @Test
    void f110_financialSessionUsedByAnotherTransferIsRejected() {
        session(new FinancialSessionToken("fs-1", "u-1", "d-1", "transfer:create"));
        when(sessions.claimFinancialSession("fs-1", "k2")).thenReturn(Optional.of("k1"));

        assertThat(useCase.checkFinancialSession("fs", USER, "k2")).isEqualTo(FinancialSessionCheck.USED);
    }

    @Test
    void f18_financialSessionOfAnotherUserIsInvalid() {
        session(new FinancialSessionToken("fs-1", "u-2", "d-1", "transfer:create"));

        assertThat(useCase.checkFinancialSession("fs", USER, "k1")).isEqualTo(FinancialSessionCheck.INVALID);
        verify(sessions, never()).claimFinancialSession(any(), any());
    }

    @Test
    void f18_financialSessionOfAnotherDeviceIsInvalid() {
        session(new FinancialSessionToken("fs-1", "u-1", "d-2", "transfer:create"));

        assertThat(useCase.checkFinancialSession("fs", USER, "k1")).isEqualTo(FinancialSessionCheck.INVALID);
    }

    @Test
    void f18_financialSessionWithAnotherScopeIsInvalid() {
        session(new FinancialSessionToken("fs-1", "u-1", "d-1", "profile:update"));

        assertThat(useCase.checkFinancialSession("fs", USER, "k1")).isEqualTo(FinancialSessionCheck.INVALID);
    }

    @Test
    void f18_badlySignedFinancialSessionIsInvalid() {
        when(tokens.verifyFinancialSession("fs")).thenThrow(new InvalidTokenException("bad signature", false));

        assertThat(useCase.checkFinancialSession("fs", USER, "k1")).isEqualTo(FinancialSessionCheck.INVALID);
    }

    @Test
    void f19_expiredFinancialSessionIsReportedAsExpired() {
        when(tokens.verifyFinancialSession("fs")).thenThrow(new InvalidTokenException("expired", true));

        assertThat(useCase.checkFinancialSession("fs", USER, "k1")).isEqualTo(FinancialSessionCheck.EXPIRED);
    }

    private void session(FinancialSessionToken token) {
        when(tokens.verifyFinancialSession("fs")).thenReturn(token);
    }
}

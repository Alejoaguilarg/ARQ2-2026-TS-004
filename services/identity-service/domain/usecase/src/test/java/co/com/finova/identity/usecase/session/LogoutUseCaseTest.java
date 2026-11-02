package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.SecurityStoreUnavailableException;
import co.com.finova.identity.model.session.gateways.RevocationStore;
import co.com.finova.identity.model.token.AccessTokenClaims;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogoutUseCaseTest {

    private static final UUID SESSION = UUID.fromString("0b6f1c2e-3d4a-4b5c-8d9e-0f1a2b3c4d5e");
    private static final UUID ANA = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");

    private final TokenSigner tokens = mock(TokenSigner.class);
    private final InMemoryRefreshTokens refreshTokens = new InMemoryRefreshTokens();
    private final RevocationStore revocations = mock(RevocationStore.class);
    private final LogoutUseCase logout = new LogoutUseCase(tokens, refreshTokens, revocations);

    @Test
    void revokesTheSessionEverywhereAndTheTokenForItsRemainingLifetime() {
        accessToken(Instant.now().plusSeconds(600));
        refreshTokens.save(new RefreshToken("a".repeat(64), SESSION, ANA, "ios-1", RefreshToken.Status.ACTIVE, Instant.now().plusSeconds(60)));
        refreshTokens.save(new RefreshToken("b".repeat(64), UUID.randomUUID(), ANA, "android-2", RefreshToken.Status.ACTIVE, Instant.now().plusSeconds(60)));

        assertThat(logout.logout("at")).isTrue();

        assertThat(refreshTokens.tokens.get("a".repeat(64)).status()).isEqualTo(RefreshToken.Status.REVOKED);
        // Only this device's session ends.
        assertThat(refreshTokens.tokens.get("b".repeat(64)).status()).isEqualTo(RefreshToken.Status.ACTIVE);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(revocations).revokeToken(eq("jti-1"), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(595), Duration.ofSeconds(600));
        verify(revocations).revokeSession(SESSION.toString(), Duration.ofSeconds(900));
    }

    @Test
    void tokenExpiringNowNeedsNoTokenRevocationButTheSessionIsStillRevoked() {
        accessToken(Instant.now().minusMillis(1));

        assertThat(logout.logout("at")).isTrue();

        verify(revocations, never()).revokeToken(any(), any());
        verify(revocations).revokeSession(SESSION.toString(), Duration.ofSeconds(900));
    }

    @Test
    void invalidTokenRevokesNothing() {
        when(tokens.verifyAccessToken("forged")).thenReturn(Optional.empty());

        assertThat(logout.logout("forged")).isFalse();
        verifyNoInteractions(revocations);
    }

    @Test
    void redisDownFailsClosedAfterRevokingInTheDatabase() {
        accessToken(Instant.now().plusSeconds(600));
        refreshTokens.save(new RefreshToken("a".repeat(64), SESSION, ANA, "ios-1", RefreshToken.Status.ACTIVE, Instant.now().plusSeconds(60)));
        doThrow(new SecurityStoreUnavailableException(new RuntimeException())).when(revocations).revokeToken(any(), any());

        assertThatThrownBy(() -> logout.logout("at")).isInstanceOf(SecurityStoreUnavailableException.class);
        assertThat(refreshTokens.tokens.get("a".repeat(64)).status()).isEqualTo(RefreshToken.Status.REVOKED);
    }

    private void accessToken(Instant expiresAt) {
        when(tokens.verifyAccessToken("at")).thenReturn(Optional.of(new AccessTokenClaims("jti-1", SESSION, ANA, expiresAt)));
    }
}

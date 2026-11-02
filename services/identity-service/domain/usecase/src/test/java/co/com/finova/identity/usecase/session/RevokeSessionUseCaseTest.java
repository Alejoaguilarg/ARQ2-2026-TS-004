package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.gateways.RevocationStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RevokeSessionUseCaseTest {

    private final RevocationStore revocations = mock(RevocationStore.class);
    private final RevokeSessionUseCase revoke = new RevokeSessionUseCase(revocations);

    @Test
    void revokesTheTokenForItsRemainingLifetimeAndTheSessionFor900Seconds() {
        revoke.execute("jti-1", "sid-1", Instant.now().plusSeconds(600));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(revocations).revokeToken(eq("jti-1"), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(595), Duration.ofSeconds(600));
        verify(revocations).revokeSession("sid-1", Duration.ofSeconds(900));
    }

    @Test
    void expiredTokenNeedsNoTokenRevocationButTheSessionIsStillRevoked() {
        revoke.execute("jti-1", "sid-1", Instant.now().minusSeconds(1));

        verify(revocations, never()).revokeToken(any(), any());
        verify(revocations).revokeSession("sid-1", Duration.ofSeconds(900));
    }
}

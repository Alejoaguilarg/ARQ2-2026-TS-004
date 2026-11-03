package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.gateways.DeviceRepository;
import co.com.finova.identity.model.token.SignedToken;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.CredentialVerifier;
import co.com.finova.identity.model.user.gateways.UserRepository;
import co.com.finova.identity.usecase.session.LoginUseCase.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginUseCaseTest {

    private static final UUID ANA = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");

    private final UserRepository users = mock(UserRepository.class);
    private final CredentialVerifier credentials = mock(CredentialVerifier.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final InMemoryRefreshTokens refreshTokens = new InMemoryRefreshTokens();
    private final TokenSigner signer = mock(TokenSigner.class);
    private final LoginUseCase login = new LoginUseCase(users, credentials, devices, refreshTokens, signer);

    LoginUseCaseTest() {
        when(signer.signAccessToken(any(), any(), any(), any()))
                .thenAnswer(call -> new SignedToken("at-for-" + call.getArgument(2), "jti", Instant.now().plusSeconds(900)));
    }

    @Test
    void f11_validCredentialsOpenASessionOnTheDevice() {
        user(User.Status.ACTIVE);
        when(credentials.matches("Finova2026!", "pw-hash")).thenReturn(true);

        Result.Issued issued = (Result.Issued) login.login("ana@example.com", "Finova2026!", "ios-1");

        RefreshToken stored = refreshTokens.byValue(issued.tokens().refreshToken());
        assertThat(issued.tokens().refreshToken()).startsWith("rt_").hasSize(46);
        assertThat(stored.status()).isEqualTo(RefreshToken.Status.ACTIVE);
        assertThat(stored.userId()).isEqualTo(ANA);
        assertThat(stored.deviceId()).isEqualTo("ios-1");
        assertThat(stored.tokenHash()).hasSize(64).doesNotContain(issued.tokens().refreshToken());
        assertThat(Duration.between(Instant.now(), stored.expiresAt())).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));
        // The access token's sid is the new family.
        assertThat(issued.tokens().accessToken()).isEqualTo("at-for-" + stored.familyId());
        verify(signer).signAccessToken(ANA, "ios-1", stored.familyId(), List.of("USER"));
        verify(devices).recordLogin(ANA, "ios-1");
    }

    @Test
    void everyLoginIsANewSession() {
        user(User.Status.ACTIVE);
        when(credentials.matches(anyString(), eq("pw-hash"))).thenReturn(true);

        login.login("ana@example.com", "Finova2026!", "ios-1");
        login.login("ana@example.com", "Finova2026!", "ios-1");

        assertThat(refreshTokens.tokens.values().stream().map(RefreshToken::familyId).distinct()).hasSize(2);
    }

    @Test
    void wrongPasswordIsInvalidCredentials() {
        user(User.Status.ACTIVE);

        assertThat(login.login("ana@example.com", "nope", "ios-1")).isInstanceOf(Result.InvalidCredentials.class);
        assertNothingIssued();
    }

    @Test
    void unknownEmailIsInvalidCredentialsAfterHashingAnyway() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThat(login.login("ghost@example.com", "x", "ios-1")).isInstanceOf(Result.InvalidCredentials.class);
        verify(credentials).matches(eq("x"), anyString());
        assertNothingIssued();
    }

    @Test
    void lockedAccountWithTheRightPasswordIsLocked() {
        user(User.Status.LOCKED);
        when(credentials.matches("Finova2026!", "pw-hash")).thenReturn(true);

        assertThat(login.login("ana@example.com", "Finova2026!", "ios-1")).isInstanceOf(Result.Locked.class);
        assertNothingIssued();
    }

    @Test
    void lockedAccountWithAWrongPasswordDoesNotRevealTheLock() {
        user(User.Status.LOCKED);

        assertThat(login.login("ana@example.com", "nope", "ios-1")).isInstanceOf(Result.InvalidCredentials.class);
    }

    @Test
    void disabledAccountIsInvalidCredentials() {
        user(User.Status.DISABLED);
        when(credentials.matches("Finova2026!", "pw-hash")).thenReturn(true);

        assertThat(login.login("ana@example.com", "Finova2026!", "ios-1")).isInstanceOf(Result.InvalidCredentials.class);
        assertNothingIssued();
    }

    private void user(User.Status status) {
        when(users.findByEmail("ana@example.com"))
                .thenReturn(Optional.of(new User(ANA, "ana@example.com", status, List.of("USER"), "pw-hash", "pin-hash", null)));
    }

    private void assertNothingIssued() {
        assertThat(refreshTokens.tokens).isEmpty();
        verify(devices, never()).recordLogin(any(), any());
        verify(signer, never()).signAccessToken(any(), any(), any(), any());
    }
}

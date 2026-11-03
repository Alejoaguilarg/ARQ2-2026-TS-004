package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.SecurityStoreUnavailableException;
import co.com.finova.identity.model.session.gateways.RevocationStore;
import co.com.finova.identity.model.token.SignedToken;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.UserRepository;
import co.com.finova.identity.usecase.session.RefreshSessionUseCase.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshSessionUseCaseTest {

    private static final UUID ANA = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final UUID FAMILY = UUID.fromString("0b6f1c2e-3d4a-4b5c-8d9e-0f1a2b3c4d5e");
    private static final String TOKEN = "rt_current";

    private final InMemoryRefreshTokens refreshTokens = new InMemoryRefreshTokens();
    private final UserRepository users = mock(UserRepository.class);
    private final RevocationStore revocations = mock(RevocationStore.class);
    private final TokenSigner signer = mock(TokenSigner.class);
    private final RefreshSessionUseCase useCase = new RefreshSessionUseCase(refreshTokens, users, revocations, signer);

    RefreshSessionUseCaseTest() {
        when(signer.signAccessToken(any(), any(), any(), any()))
                .thenReturn(new SignedToken("at", "jti", Instant.now().plusSeconds(900)));
        activeUser(User.Status.ACTIVE);
    }

    @Test
    void f12_validTokenIsRotatedIntoANewPairOfTheSameSession() {
        stored(RefreshToken.Status.ACTIVE, "ios-1", Instant.now().plusSeconds(60));

        Result.Issued issued = (Result.Issued) useCase.refresh(TOKEN, "ios-1");

        assertThat(refreshTokens.byValue(TOKEN).status()).isEqualTo(RefreshToken.Status.ROTATED);
        RefreshToken next = refreshTokens.byValue(issued.tokens().refreshToken());
        assertThat(next.status()).isEqualTo(RefreshToken.Status.ACTIVE);
        assertThat(next.familyId()).isEqualTo(FAMILY);
        assertThat(issued.tokens().refreshToken()).isNotEqualTo(TOKEN);
        verify(signer).signAccessToken(ANA, "ios-1", FAMILY, List.of("USER"));
    }

    @Test
    void f13_rotatedTokenPresentedAgainRevokesTheWholeSession() {
        stored(RefreshToken.Status.ACTIVE, "ios-1", Instant.now().plusSeconds(60));
        Result.Issued issued = (Result.Issued) useCase.refresh(TOKEN, "ios-1");

        Result reuse = useCase.refresh(TOKEN, "ios-1");

        assertThat(reuse).isInstanceOf(Result.Reused.class);
        assertThat(refreshTokens.tokens.values()).allMatch(t -> t.status() == RefreshToken.Status.REVOKED);
        verify(revocations).revokeSession(FAMILY.toString(), Duration.ofSeconds(900));
        // The legitimate holder's newer token died with the session.
        assertThat(useCase.refresh(issued.tokens().refreshToken(), "ios-1")).isInstanceOf(Result.Invalid.class);
    }

    @Test
    void f13_rotatedTokenFromAnotherDeviceStillRevokesTheSession() {
        stored(RefreshToken.Status.ROTATED, "ios-1", Instant.now().plusSeconds(60));

        assertThat(useCase.refresh(TOKEN, "attacker-device")).isInstanceOf(Result.Reused.class);
        verify(revocations).revokeSession(FAMILY.toString(), Duration.ofSeconds(900));
    }

    @Test
    void losingTheRotationRaceIsTreatedAsReuse() {
        RefreshToken active = stored(RefreshToken.Status.ACTIVE, "ios-1", Instant.now().plusSeconds(60));
        InMemoryRefreshTokens racing = new InMemoryRefreshTokens() {
            @Override
            public boolean markRotated(String tokenHash) {
                return false;
            }
        };
        racing.save(active);

        assertThat(new RefreshSessionUseCase(racing, users, revocations, signer).refresh(TOKEN, "ios-1"))
                .isInstanceOf(Result.Reused.class);
    }

    @Test
    void unknownTokenIsInvalid() {
        assertThat(useCase.refresh("rt_never-issued", "ios-1")).isInstanceOf(Result.Invalid.class);
    }

    @Test
    void tokenFromAnotherDeviceIsInvalidAndNotRotated() {
        stored(RefreshToken.Status.ACTIVE, "ios-1", Instant.now().plusSeconds(60));

        assertThat(useCase.refresh(TOKEN, "android-2")).isInstanceOf(Result.Invalid.class);
        assertThat(refreshTokens.byValue(TOKEN).status()).isEqualTo(RefreshToken.Status.ACTIVE);
    }

    @Test
    void expiredTokenIsInvalid() {
        stored(RefreshToken.Status.ACTIVE, "ios-1", Instant.now().minusSeconds(1));

        assertThat(useCase.refresh(TOKEN, "ios-1")).isInstanceOf(Result.Invalid.class);
    }

    @Test
    void revokedTokenIsInvalidWithoutRevokingAgain() {
        stored(RefreshToken.Status.REVOKED, "ios-1", Instant.now().plusSeconds(60));

        assertThat(useCase.refresh(TOKEN, "ios-1")).isInstanceOf(Result.Invalid.class);
        verify(revocations, never()).revokeSession(any(), any());
    }

    @Test
    void userNoLongerActiveEndsTheSession() {
        stored(RefreshToken.Status.ACTIVE, "ios-1", Instant.now().plusSeconds(60));
        activeUser(User.Status.DISABLED);

        assertThat(useCase.refresh(TOKEN, "ios-1")).isInstanceOf(Result.Invalid.class);
        assertThat(refreshTokens.byValue(TOKEN).status()).isEqualTo(RefreshToken.Status.REVOKED);
        verify(revocations).revokeSession(FAMILY.toString(), RevocationStore.SESSION_TTL);
    }

    @Test
    void aTokenSavedAfterItsSessionWasRevokedCannotReviveIt() {
        // A refresh rotated the old token, then a logout revoked the family, then the refresh saved its new token.
        refreshTokens.save(new RefreshToken(SessionTokens.hash("rt_old"), FAMILY, ANA, "ios-1",
                RefreshToken.Status.REVOKED, Instant.now().plusSeconds(60)));
        stored(RefreshToken.Status.ACTIVE, "ios-1", Instant.now().plusSeconds(60));

        assertThat(useCase.refresh(TOKEN, "ios-1")).isInstanceOf(Result.Invalid.class);

        assertThat(refreshTokens.byValue(TOKEN).status()).isEqualTo(RefreshToken.Status.REVOKED);
        verify(revocations).revokeSession(FAMILY.toString(), RevocationStore.SESSION_TTL);
        verify(signer, never()).signAccessToken(any(), any(), any(), any());
    }

    @Test
    void redisDownDuringReuseFailsClosedAfterRevokingInTheDatabase() {
        stored(RefreshToken.Status.ROTATED, "ios-1", Instant.now().plusSeconds(60));
        doThrow(new SecurityStoreUnavailableException(new RuntimeException()))
                .when(revocations).revokeSession(any(), any());

        assertThatThrownBy(() -> useCase.refresh(TOKEN, "ios-1")).isInstanceOf(SecurityStoreUnavailableException.class);
        assertThat(refreshTokens.byValue(TOKEN).status()).isEqualTo(RefreshToken.Status.REVOKED);
    }

    private RefreshToken stored(RefreshToken.Status status, String deviceId, Instant expiresAt) {
        RefreshToken token = new RefreshToken(SessionTokens.hash(TOKEN), FAMILY, ANA, deviceId, status, expiresAt);
        refreshTokens.save(token);
        return token;
    }

    private void activeUser(User.Status status) {
        when(users.findById(ANA)).thenReturn(Optional.of(new User(ANA, "ana@example.com", status, List.of("USER"), "pw", "pin", null)));
    }
}

package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.token.AccessTokenClaims;
import co.com.finova.identity.model.token.SignedToken;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.CredentialVerifier;
import co.com.finova.identity.model.user.gateways.OneTimeCodeVerifier;
import co.com.finova.identity.model.user.gateways.UserRepository;
import co.com.finova.identity.usecase.session.FinancialSessionUseCase.Method;
import co.com.finova.identity.usecase.session.FinancialSessionUseCase.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinancialSessionUseCaseTest {

    private static final UUID ANA = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final SignedToken SESSION = new SignedToken("fs", "fs-jti", Instant.now().plusSeconds(300));

    private final TokenSigner tokens = mock(TokenSigner.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CredentialVerifier pins = mock(CredentialVerifier.class);
    private final OneTimeCodeVerifier codes = mock(OneTimeCodeVerifier.class);
    private final FinancialSessionUseCase useCase = new FinancialSessionUseCase(tokens, users, pins, codes);

    FinancialSessionUseCaseTest() {
        when(tokens.verifyAccessToken("at"))
                .thenReturn(Optional.of(new AccessTokenClaims("jti", UUID.randomUUID(), ANA, "ios-1", Instant.now().plusSeconds(600))));
        when(tokens.signFinancialSession(any(), any(), any(), any())).thenReturn(SESSION);
    }

    @Test
    void f16_rightPinIssuesASessionBoundToTheUserAndDevice() {
        user(User.Status.ACTIVE, "otp-enc");
        when(pins.matches("4821", "pin-hash")).thenReturn(true);

        assertThat(useCase.open("at", Method.PIN, "4821", "transfer:create")).isEqualTo(new Result.Issued(SESSION));
        verify(tokens).signFinancialSession(ANA, "ios-1", "transfer:create", "PIN");
        verifyNoInteractions(codes);
    }

    @Test
    void f16_rightOtpCodeIssuesASession() {
        user(User.Status.ACTIVE, "otp-enc");
        when(codes.matches("123456", "otp-enc")).thenReturn(true);

        assertThat(useCase.open("at", Method.OTP, "123456", "transfer:create")).isInstanceOf(Result.Issued.class);
        verify(tokens).signFinancialSession(ANA, "ios-1", "transfer:create", "OTP");
        verifyNoInteractions(pins);
    }

    @Test
    void wrongPinIsStepUpFailed() {
        user(User.Status.ACTIVE, "otp-enc");

        assertThat(useCase.open("at", Method.PIN, "0000", "transfer:create")).isInstanceOf(Result.StepUpFailed.class);
        verify(tokens, never()).signFinancialSession(any(), any(), any(), any());
    }

    @Test
    void wrongOtpIsStepUpFailed() {
        user(User.Status.ACTIVE, "otp-enc");

        assertThat(useCase.open("at", Method.OTP, "000000", "transfer:create")).isInstanceOf(Result.StepUpFailed.class);
    }

    @Test
    void otpWithoutAnEnrolledSecretIsStepUpFailed() {
        user(User.Status.ACTIVE, null);

        assertThat(useCase.open("at", Method.OTP, "123456", "transfer:create")).isInstanceOf(Result.StepUpFailed.class);
        verifyNoInteractions(codes);
    }

    @Test
    void invalidAccessTokenIsUnauthorized() {
        when(tokens.verifyAccessToken("forged")).thenReturn(Optional.empty());

        assertThat(useCase.open("forged", Method.PIN, "4821", "transfer:create")).isInstanceOf(Result.Unauthorized.class);
        verifyNoInteractions(pins);
    }

    @Test
    void userNoLongerActiveIsUnauthorized() {
        user(User.Status.LOCKED, "otp-enc");

        assertThat(useCase.open("at", Method.PIN, "4821", "transfer:create")).isInstanceOf(Result.Unauthorized.class);
        verifyNoInteractions(pins);
    }

    private void user(User.Status status, String otpSecret) {
        when(users.findById(ANA)).thenReturn(Optional.of(
                new User(ANA, "ana@example.com", status, List.of("USER"), "pw-hash", "pin-hash", otpSecret)));
    }
}

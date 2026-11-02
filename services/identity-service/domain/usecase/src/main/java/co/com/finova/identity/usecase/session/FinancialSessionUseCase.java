package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.token.AccessTokenClaims;
import co.com.finova.identity.model.token.SignedToken;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.CredentialVerifier;
import co.com.finova.identity.model.user.gateways.OneTimeCodeVerifier;
import co.com.finova.identity.model.user.gateways.UserRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

// spec.md §3.6 step-up: a PIN or TOTP code buys a 5-minute financial session bound to the user and
// device of the access token. The Gateway counts STEP_UP_FAILED answers for the step-up lockout and
// makes each session single use.
@RequiredArgsConstructor
public class FinancialSessionUseCase {

    public static final String TRANSFER_SCOPE = "transfer:create";

    public enum Method { PIN, OTP }

    public sealed interface Result {
        record Issued(SignedToken token) implements Result {
        }

        record Unauthorized() implements Result {
        }

        record StepUpFailed() implements Result {
        }
    }

    private final TokenSigner tokens;
    private final UserRepository users;
    private final CredentialVerifier pins;
    private final OneTimeCodeVerifier codes;

    public Result open(String accessToken, Method method, String credential, String scope) {
        Optional<AccessTokenClaims> claims = tokens.verifyAccessToken(accessToken);
        Optional<User> user = claims.flatMap(c -> users.findById(c.userId())).filter(u -> u.status() == User.Status.ACTIVE);
        if (user.isEmpty()) {
            return new Result.Unauthorized();
        }
        boolean verified = switch (method) {
            case PIN -> pins.matches(credential, user.get().pinHash());
            case OTP -> user.get().otpSecret() != null && codes.matches(credential, user.get().otpSecret());
        };
        if (!verified) {
            return new Result.StepUpFailed();
        }
        return new Result.Issued(tokens.signFinancialSession(user.get().userId(), claims.get().deviceId(), scope, method.name()));
    }
}

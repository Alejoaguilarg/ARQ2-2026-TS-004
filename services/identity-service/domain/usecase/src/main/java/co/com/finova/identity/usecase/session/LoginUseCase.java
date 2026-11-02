package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.TokenPair;
import co.com.finova.identity.model.session.gateways.DeviceRepository;
import co.com.finova.identity.model.session.gateways.RefreshTokenRepository;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.CredentialVerifier;
import co.com.finova.identity.model.user.gateways.UserRepository;

import java.util.Optional;
import java.util.UUID;

// spec.md §3.3: verifies the password and opens a new session (refresh-token family) on the device.
// Lockout after 5 failures is the Gateway's job; it counts the INVALID_CREDENTIALS answers.
public class LoginUseCase {

    // Verified when the email is unknown, so the answer takes as long as for a wrong password.
    private static final String DUMMY_HASH =
            "$argon2id$v=19$m=16384,t=2,p=1$a8l55xW7EFJfIDLXegE2Pg$08ZXrkRVpVK6ffZnlwQ8O0N6D5/t+A5Z1Hm4u8PB1aU";

    public sealed interface Result {
        record Issued(TokenPair tokens) implements Result {
        }

        record InvalidCredentials() implements Result {
        }

        record Locked() implements Result {
        }
    }

    private final UserRepository users;
    private final CredentialVerifier credentials;
    private final DeviceRepository devices;
    private final SessionTokens tokens;

    public LoginUseCase(UserRepository users, CredentialVerifier credentials, DeviceRepository devices,
                        RefreshTokenRepository refreshTokens, TokenSigner signer) {
        this.users = users;
        this.credentials = credentials;
        this.devices = devices;
        this.tokens = new SessionTokens(signer, refreshTokens);
    }

    public Result login(String email, String password, String deviceId) {
        Optional<User> found = users.findByEmail(email);
        boolean passwordMatches = credentials.matches(password, found.map(User::passwordHash).orElse(DUMMY_HASH));
        if (found.isEmpty() || !passwordMatches) {
            return new Result.InvalidCredentials();
        }
        User user = found.get();
        // Only someone with the right password learns the account is locked; DISABLED stays hidden.
        switch (user.status()) {
            case LOCKED -> {
                return new Result.Locked();
            }
            case DISABLED -> {
                return new Result.InvalidCredentials();
            }
            case ACTIVE -> {
            }
        }
        devices.recordLogin(user.userId(), deviceId);
        return new Result.Issued(tokens.issue(user, deviceId, UUID.randomUUID()));
    }
}

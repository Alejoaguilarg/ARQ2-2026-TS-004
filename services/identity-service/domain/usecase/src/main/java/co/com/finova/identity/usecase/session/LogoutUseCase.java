package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.gateways.RefreshTokenRepository;
import co.com.finova.identity.model.session.gateways.RevocationStore;
import co.com.finova.identity.model.token.AccessTokenClaims;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// spec.md §3.5: ends the device's session. The refresh-token family becomes REVOKED, and Redis gets
// revoked:jti (remaining lifetime) and revoked:sid (900 s) so the Gateway rejects the session at once.
@RequiredArgsConstructor
public class LogoutUseCase {

    private final TokenSigner tokens;
    private final RefreshTokenRepository refreshTokens;
    private final RevocationStore revocations;

    // False when the token is not a valid access token of this Identity.
    public boolean logout(String accessToken) {
        Optional<AccessTokenClaims> verified = tokens.verifyAccessToken(accessToken);
        if (verified.isEmpty()) {
            return false;
        }
        AccessTokenClaims claims = verified.get();
        refreshTokens.revokeFamily(claims.sessionId());
        Duration remaining = Duration.between(Instant.now(), claims.expiresAt());
        if (remaining.isPositive()) {
            revocations.revokeToken(claims.jti(), remaining);
        }
        revocations.revokeSession(claims.sessionId().toString(), RevocationStore.SESSION_TTL);
        return true;
    }
}

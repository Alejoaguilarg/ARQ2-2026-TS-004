package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.TokenPair;
import co.com.finova.identity.model.session.gateways.RefreshTokenRepository;
import co.com.finova.identity.model.session.gateways.RevocationStore;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.UserRepository;

import java.time.Instant;
import java.util.Optional;

// spec.md §3.4: rotates the refresh token inside its session. A ROTATED token presented again
// means it leaked: the whole session is revoked, here and in Redis (F1.3).
public class RefreshSessionUseCase {

    public sealed interface Result {
        record Issued(TokenPair tokens) implements Result {
        }

        record Invalid() implements Result {
        }

        record Reused() implements Result {
        }
    }

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final RevocationStore revocations;
    private final SessionTokens tokens;

    public RefreshSessionUseCase(RefreshTokenRepository refreshTokens, UserRepository users,
                                 RevocationStore revocations, TokenSigner signer) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.revocations = revocations;
        this.tokens = new SessionTokens(signer, refreshTokens);
    }

    public Result refresh(String refreshToken, String deviceId) {
        Optional<RefreshToken> found = refreshTokens.findByHash(SessionTokens.hash(refreshToken));
        if (found.isEmpty()) {
            return new Result.Invalid();
        }
        RefreshToken presented = found.get();
        // Reuse first: a rotated token from another device is exactly what a stolen one looks like.
        switch (presented.status()) {
            case REVOKED -> {
                return new Result.Invalid();
            }
            case ROTATED -> {
                return revoke(presented);
            }
            case ACTIVE -> {
            }
        }
        if (!presented.deviceId().equals(deviceId) || !presented.expiresAt().isAfter(Instant.now())) {
            return new Result.Invalid();
        }
        // An ACTIVE token in a revoked family was saved by a refresh that raced a logout or a reuse revocation
        // (rotate, then the family is revoked, then the new token is saved). REVOKED is final for the session, so
        // the token is refused and the family revoked again, Redis included, instead of reviving the session once
        // revoked:sid expires.
        if (refreshTokens.isFamilyRevoked(presented.familyId())) {
            endSession(presented);
            return new Result.Invalid();
        }
        // Two refreshes with the same token: only one rotates it; the other is treated as reuse.
        if (!refreshTokens.markRotated(presented.tokenHash())) {
            return revoke(presented);
        }
        Optional<User> user = users.findById(presented.userId()).filter(u -> u.status() == User.Status.ACTIVE);
        if (user.isEmpty()) {
            endSession(presented);
            return new Result.Invalid();
        }
        return new Result.Issued(tokens.issue(user.get(), deviceId, presented.familyId()));
    }

    private Result revoke(RefreshToken presented) {
        endSession(presented);
        return new Result.Reused();
    }

    // Revoked in identity_db and in Redis, so the Gateway also refuses the session's access tokens at once.
    private void endSession(RefreshToken presented) {
        refreshTokens.revokeFamily(presented.familyId());
        revocations.revokeSession(presented.familyId().toString(), RevocationStore.SESSION_TTL);
    }
}

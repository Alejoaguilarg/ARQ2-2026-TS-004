package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.TokenPair;
import co.com.finova.identity.model.session.gateways.RefreshTokenRepository;
import co.com.finova.identity.model.token.SignedToken;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

// Issues a token pair inside a session (refresh-token family); shared by login and refresh.
final class SessionTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenSigner signer;
    private final RefreshTokenRepository refreshTokens;

    SessionTokens(TokenSigner signer, RefreshTokenRepository refreshTokens) {
        this.signer = signer;
        this.refreshTokens = refreshTokens;
    }

    TokenPair issue(User user, String deviceId, UUID familyId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String refreshToken = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        refreshTokens.save(new RefreshToken(hash(refreshToken), familyId, user.userId(), deviceId,
                RefreshToken.Status.ACTIVE, Instant.now().plus(RefreshToken.TTL)));
        SignedToken access = signer.signAccessToken(user.userId(), deviceId, familyId, user.roles());
        return new TokenPair(access.value(), refreshToken);
    }

    static String hash(String refreshToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

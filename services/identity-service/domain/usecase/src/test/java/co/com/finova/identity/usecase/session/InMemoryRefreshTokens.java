package co.com.finova.identity.usecase.session;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.gateways.RefreshTokenRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class InMemoryRefreshTokens implements RefreshTokenRepository {

    final Map<String, RefreshToken> tokens = new LinkedHashMap<>();

    @Override
    public void save(RefreshToken token) {
        tokens.put(token.tokenHash(), token);
    }

    @Override
    public Optional<RefreshToken> findByHash(String tokenHash) {
        return Optional.ofNullable(tokens.get(tokenHash));
    }

    @Override
    public boolean markRotated(String tokenHash) {
        RefreshToken token = tokens.get(tokenHash);
        if (token == null || token.status() != RefreshToken.Status.ACTIVE) {
            return false;
        }
        tokens.put(tokenHash, with(token, RefreshToken.Status.ROTATED));
        return true;
    }

    @Override
    public void revokeFamily(UUID familyId) {
        tokens.replaceAll((hash, token) -> token.familyId().equals(familyId) ? with(token, RefreshToken.Status.REVOKED) : token);
    }

    RefreshToken byValue(String refreshToken) {
        return tokens.get(SessionTokens.hash(refreshToken));
    }

    private static RefreshToken with(RefreshToken token, RefreshToken.Status status) {
        return new RefreshToken(token.tokenHash(), token.familyId(), token.userId(), token.deviceId(), status, token.expiresAt());
    }
}

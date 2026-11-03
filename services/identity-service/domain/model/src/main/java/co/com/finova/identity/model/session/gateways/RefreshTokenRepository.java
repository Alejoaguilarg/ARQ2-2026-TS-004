package co.com.finova.identity.model.session.gateways;

import co.com.finova.identity.model.session.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    void save(RefreshToken token);

    Optional<RefreshToken> findByHash(String tokenHash);

    // ACTIVE -> ROTATED only if still ACTIVE; false when another request rotated it first.
    boolean markRotated(String tokenHash);

    // Every token of the family (session) becomes REVOKED.
    void revokeFamily(UUID familyId);
}

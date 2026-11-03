package co.com.finova.identity.jpa;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.gateways.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository tokens;

    @Override
    public void save(RefreshToken token) {
        tokens.save(new RefreshTokenEntity(token));
    }

    @Override
    public Optional<RefreshToken> findByHash(String tokenHash) {
        return tokens.findById(tokenHash).map(RefreshTokenEntity::toRefreshToken);
    }

    @Override
    public boolean markRotated(String tokenHash) {
        return tokens.markRotated(tokenHash) == 1;
    }

    @Override
    public void revokeFamily(UUID familyId) {
        tokens.revokeFamily(familyId);
    }
}

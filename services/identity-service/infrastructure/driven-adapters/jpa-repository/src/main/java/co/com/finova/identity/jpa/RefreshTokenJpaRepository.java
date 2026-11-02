package co.com.finova.identity.jpa;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends Repository<RefreshTokenEntity, String> {

    RefreshTokenEntity save(RefreshTokenEntity token);

    Optional<RefreshTokenEntity> findById(String tokenHash);

    // Conditional update: of two concurrent refreshes with one token, only one gets 1 row.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity t SET t.status = 'ROTATED' WHERE t.tokenHash = :hash AND t.status = 'ACTIVE'")
    int markRotated(@Param("hash") String hash);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity t SET t.status = 'REVOKED' WHERE t.familyId = :familyId")
    int revokeFamily(@Param("familyId") UUID familyId);
}

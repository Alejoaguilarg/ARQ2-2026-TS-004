package co.com.finova.identity.jpa;

import co.com.finova.identity.model.session.RefreshToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenEntity {

    @Id
    @Column(name = "token_hash", columnDefinition = "bpchar(64)")
    private String tokenHash;

    @Column(name = "family_id")
    private UUID familyId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "device_id")
    private String deviceId;

    @Enumerated(EnumType.STRING)
    private RefreshToken.Status status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    RefreshTokenEntity(RefreshToken token) {
        this.tokenHash = token.tokenHash();
        this.familyId = token.familyId();
        this.userId = token.userId();
        this.deviceId = token.deviceId();
        this.status = token.status();
        this.expiresAt = token.expiresAt();
    }

    RefreshToken toRefreshToken() {
        return new RefreshToken(tokenHash, familyId, userId, deviceId, status, expiresAt);
    }
}

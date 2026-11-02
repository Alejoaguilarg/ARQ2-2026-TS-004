package co.com.finova.identity.model.session;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// spec.md §2 RefreshToken. Only the SHA-256 of the token travels past the use case.
public record RefreshToken(String tokenHash, UUID familyId, UUID userId, String deviceId, Status status,
                           Instant expiresAt) {

    public static final Duration TTL = Duration.ofDays(7);

    public enum Status { ACTIVE, ROTATED, REVOKED }
}

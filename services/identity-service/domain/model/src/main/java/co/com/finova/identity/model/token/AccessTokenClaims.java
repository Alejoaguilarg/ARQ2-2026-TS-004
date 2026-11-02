package co.com.finova.identity.model.token;

import java.time.Instant;
import java.util.UUID;

// What logout needs from a verified access token.
public record AccessTokenClaims(String jti, UUID sessionId, UUID userId, Instant expiresAt) {
}

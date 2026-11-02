package co.com.finova.identity.model.token;

import java.time.Instant;
import java.util.UUID;

// What logout and step-up need from a verified access token.
public record AccessTokenClaims(String jti, UUID sessionId, UUID userId, String deviceId, Instant expiresAt) {
}

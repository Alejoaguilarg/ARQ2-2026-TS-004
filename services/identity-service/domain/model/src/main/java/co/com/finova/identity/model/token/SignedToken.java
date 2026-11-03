package co.com.finova.identity.model.token;

import java.time.Instant;

public record SignedToken(String value, String jti, Instant expiresAt) {
}

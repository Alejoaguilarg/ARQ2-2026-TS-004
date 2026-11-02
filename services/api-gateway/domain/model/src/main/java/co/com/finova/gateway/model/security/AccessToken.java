package co.com.finova.gateway.model.security;

import java.util.List;

// Verified claims of an access token (spec.md AccessTokenClaims).
public record AccessToken(String jti, String userId, String deviceId, String sessionId, List<String> roles) {

    public AccessToken {
        roles = List.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}

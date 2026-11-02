package co.com.finova.gateway.model.security;

// Verified claims of an X-Financial-Session token (spec.md FinancialSession).
public record FinancialSessionToken(String jti, String userId, String deviceId, String scope) {
}

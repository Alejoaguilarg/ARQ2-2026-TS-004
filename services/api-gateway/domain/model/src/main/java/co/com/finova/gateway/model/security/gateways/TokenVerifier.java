package co.com.finova.gateway.model.security.gateways;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.FinancialSessionToken;

// Verifies tokens issued by Identity (RS256, keys from its JWKS, spec.md §3.1.3).
// Both methods throw InvalidTokenException for a bad token and
// SecurityStoreUnavailableException when the keys cannot be fetched.
public interface TokenVerifier {

    AccessToken verifyAccessToken(String token);

    FinancialSessionToken verifyFinancialSession(String token);
}

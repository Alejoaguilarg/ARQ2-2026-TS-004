package co.com.finova.gateway.model.security.gateways;

import java.util.Optional;

// Every method throws SecurityStoreUnavailableException when the store cannot be reached.
public interface SessionStore {

    // True if revoked:jti:{jti} or revoked:sid:{sid} exists (spec.md §2.1).
    boolean isRevoked(String jti, String sid);

    // Atomically marks fs:used:{jti} with the Idempotency-Key; returns the key that had already used it, if any.
    Optional<String> claimFinancialSession(String jti, String idempotencyKey);
}

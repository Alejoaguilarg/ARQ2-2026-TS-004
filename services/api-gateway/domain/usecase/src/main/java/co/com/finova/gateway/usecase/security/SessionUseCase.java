package co.com.finova.gateway.usecase.security;

import co.com.finova.gateway.model.security.gateways.SessionStore;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

// spec.md §3.1: revocation check (step 1) and single-use financial session (step 3).
@RequiredArgsConstructor
public class SessionUseCase {

    private final SessionStore sessions;

    public boolean isRevoked(String jti, String sid) {
        return sessions.isRevoked(jti, sid);
    }

    // A financial session serves one transfer intent: a retry with the same Idempotency-Key may reuse it.
    public boolean claimFinancialSession(String jti, String idempotencyKey) {
        Optional<String> usedBy = sessions.claimFinancialSession(jti, idempotencyKey);
        return usedBy.isEmpty() || usedBy.get().equals(idempotencyKey);
    }
}

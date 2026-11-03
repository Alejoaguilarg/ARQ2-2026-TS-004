package co.com.finova.identity.model.user.gateways;

// Checks a password or PIN against its stored hash (argon2, spec.md §2 User).
public interface CredentialVerifier {

    boolean matches(String rawCredential, String hash);
}

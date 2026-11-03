package co.com.finova.identity.argon2;

import co.com.finova.identity.model.user.gateways.CredentialVerifier;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Argon2CredentialVerifier implements CredentialVerifier {

    // Parameters only matter when hashing; matching reads them from the stored hash.
    private final Argon2PasswordEncoder argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Override
    public boolean matches(String rawCredential, String hash) {
        return argon2.matches(rawCredential, hash);
    }
}

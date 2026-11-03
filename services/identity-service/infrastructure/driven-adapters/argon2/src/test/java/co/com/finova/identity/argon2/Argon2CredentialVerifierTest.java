package co.com.finova.identity.argon2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2CredentialVerifierTest {

    // ana's hashes, copied from V2__demo_users.sql (generated with Python's argon2-cffi).
    private static final String ANA_PASSWORD =
            "$argon2id$v=19$m=16384,t=2,p=1$T3+K0Upf5KAOmYUHKQh/sw$EVZI61agdvqnE8uGvuYFYdDRbJok+wiQVeBaHpD7baE";
    private static final String ANA_PIN =
            "$argon2id$v=19$m=16384,t=2,p=1$TZ8c3WGk1QtP3ulo3KuJag$vmY1ZLtnzJtOsNe8qrfdDGUcGXH+JJGJ5bsymfefosU";

    private final Argon2CredentialVerifier verifier = new Argon2CredentialVerifier();

    @Test
    void seededHashesMatchTheirDocumentedCredentials() {
        assertThat(verifier.matches("Finova2026!", ANA_PASSWORD)).isTrue();
        assertThat(verifier.matches("4821", ANA_PIN)).isTrue();
    }

    @Test
    void wrongCredentialDoesNotMatch() {
        assertThat(verifier.matches("finova2026!", ANA_PASSWORD)).isFalse();
        assertThat(verifier.matches("4822", ANA_PIN)).isFalse();
    }

    @Test
    void malformedHashDoesNotMatch() {
        assertThat(verifier.matches("Finova2026!", "not-a-hash")).isFalse();
    }
}

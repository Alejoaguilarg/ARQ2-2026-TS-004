package co.com.finova.identity.totp;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpCodeVerifierTest {

    // Development key from application.yaml and ana's secret from V3__demo_otp_secrets.sql / README.
    private static final String DEV_KEY = "/zUl5NaRDlw5Alr4Q5WD75YZ+SO5WWh/zjaqqr0fgzY=";
    private static final String ANA_ENCRYPTED =
            "un8kCcEv6l5is6yZoRwvBNWmjUyLRVMjWbbQA70hHVRSlvRY1/m3cPvyCqebNzKGqR4OGmgvtErkWfoT";
    // RFC 6238 Appendix B, SHA1 seed "12345678901234567890".
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void codesMatchTheRfc6238TestVectors() {
        byte[] seed = TotpCodeVerifier.base32(RFC_SECRET);

        assertThat(new String(seed, StandardCharsets.US_ASCII)).isEqualTo("12345678901234567890");
        assertThat(TotpCodeVerifier.code(seed, 59 / 30)).isEqualTo("287082");
        assertThat(TotpCodeVerifier.code(seed, 1111111109L / 30)).isEqualTo("081804");
        assertThat(TotpCodeVerifier.code(seed, 1234567890L / 30)).isEqualTo("005924");
        assertThat(TotpCodeVerifier.code(seed, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void seededDemoSecretDecryptsWithTheDevelopmentKey() {
        assertThat(new TotpCodeVerifier(DEV_KEY).decrypt(ANA_ENCRYPTED)).isEqualTo("JOJYF2MFLYMVTZVHPQXBEZXZPG3BSRZ2");
    }

    @Test
    void acceptsTheCurrentCodeAndOneStepOfDriftEitherWay() {
        TotpCodeVerifier verifier = at(1111111109L);
        String encrypted = encrypt(RFC_SECRET);
        byte[] seed = TotpCodeVerifier.base32(RFC_SECRET);
        long step = 1111111109L / 30;

        assertThat(verifier.matches("081804", encrypted)).isTrue();
        assertThat(verifier.matches(TotpCodeVerifier.code(seed, step - 1), encrypted)).isTrue();
        assertThat(verifier.matches(TotpCodeVerifier.code(seed, step + 1), encrypted)).isTrue();
        assertThat(verifier.matches(TotpCodeVerifier.code(seed, step - 2), encrypted)).isFalse();
        assertThat(verifier.matches(TotpCodeVerifier.code(seed, step + 2), encrypted)).isFalse();
    }

    @Test
    void wrongOrMalformedCodesDoNotMatch() {
        TotpCodeVerifier verifier = at(1111111109L);
        String encrypted = encrypt(RFC_SECRET);

        assertThat(verifier.matches("081805", encrypted)).isFalse();
        assertThat(verifier.matches("81804", encrypted)).isFalse();
        assertThat(verifier.matches("0818044", encrypted)).isFalse();
        assertThat(verifier.matches("08180a", encrypted)).isFalse();
        assertThat(verifier.matches(null, encrypted)).isFalse();
    }

    @Test
    void secretEncryptedWithAnotherKeyIsAServerFault() {
        String otherKey = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> new TotpCodeVerifier(otherKey).matches("081804", encrypt(RFC_SECRET)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void base32IgnoresPaddingAndCaseAndRejectsOtherCharacters() {
        assertThat(TotpCodeVerifier.base32("mzxw6===")).isEqualTo("foo".getBytes(StandardCharsets.US_ASCII));
        // 'A' is the zero digit.
        assertThat(TotpCodeVerifier.base32("AE======")).containsExactly(1);
        assertThatThrownBy(() -> TotpCodeVerifier.base32("MZXW1")).isInstanceOf(IllegalStateException.class);
    }

    private static TotpCodeVerifier at(long epochSecond) {
        return new TotpCodeVerifier(DEV_KEY, Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    private static String encrypt(String base32Secret) {
        try {
            byte[] iv = new byte[12];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(DEV_KEY), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(base32Secret.getBytes(StandardCharsets.US_ASCII));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

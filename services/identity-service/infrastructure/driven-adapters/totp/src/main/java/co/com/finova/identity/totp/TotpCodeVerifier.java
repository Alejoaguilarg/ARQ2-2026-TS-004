package co.com.finova.identity.totp;

import co.com.finova.identity.model.user.gateways.OneTimeCodeVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

// RFC 6238 TOTP: HMAC-SHA1, 30-second steps, 6 digits, one step of clock drift either way.
// The secret is stored as base64(iv || AES-256-GCM(base32 secret)).
@Component
public class TotpCodeVerifier implements OneTimeCodeVerifier {

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");
    private static final long STEP_SECONDS = 30;
    private static final int GCM_IV_BYTES = 12;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecretKeySpec encryptionKey;
    private final Clock clock;

    @Autowired
    public TotpCodeVerifier(@Value("${finova.security.otp-key}") String base64Key) {
        this(base64Key, Clock.systemUTC());
    }

    TotpCodeVerifier(String base64Key, Clock clock) {
        this.encryptionKey = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
        this.clock = clock;
    }

    @Override
    public boolean matches(String code, String encryptedSecret) {
        if (code == null || !SIX_DIGITS.matcher(code).matches()) {
            return false;
        }
        byte[] secret = base32(decrypt(encryptedSecret));
        long step = clock.instant().getEpochSecond() / STEP_SECONDS;
        boolean matched = false;
        for (long drift = -1; drift <= 1; drift++) {
            // Constant-time compare, and no early exit, so timing does not reveal which step matched.
            matched |= MessageDigest.isEqual(code(secret, step + drift).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII));
        }
        return matched;
    }

    // A secret that does not decrypt means a wrong key or corrupted data: a server fault, not a wrong code.
    String decrypt(String encryptedSecret) {
        try {
            byte[] data = Base64.getDecoder().decode(encryptedSecret);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, data, 0, GCM_IV_BYTES));
            return new String(cipher.doFinal(data, GCM_IV_BYTES, data.length - GCM_IV_BYTES), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt the OTP secret", e);
        }
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ByteBuffer.wrap(Arrays.copyOfRange(hash, offset, offset + 4)).getInt() & 0x7fffffff;
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] base32(String encoded) {
        String clean = encoded.replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalStateException("OTP secret is not base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }
}

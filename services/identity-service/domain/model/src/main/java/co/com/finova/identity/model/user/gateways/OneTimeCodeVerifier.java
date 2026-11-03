package co.com.finova.identity.model.user.gateways;

// Checks a 6-digit TOTP code (RFC 6238) against the user's encrypted OTP secret.
public interface OneTimeCodeVerifier {

    boolean matches(String code, String encryptedSecret);
}

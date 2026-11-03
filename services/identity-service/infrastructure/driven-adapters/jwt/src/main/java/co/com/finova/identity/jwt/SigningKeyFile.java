package co.com.finova.identity.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The token signing key, kept in a PKCS#8 PEM file so it survives restarts and every Identity instance signs with
 * the same key. A file that does not exist yet is created once with a new 2048-bit key; when two instances start
 * together, the first write wins and the other reads it. The kid is the key's RFC 7638 thumbprint, so it is the
 * same for the same key everywhere.
 */
final class SigningKeyFile {

    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";

    private SigningKeyFile() {
    }

    static RSAKey loadOrCreate(Path file) {
        try {
            if (!Files.exists(file)) {
                create(file);
            }
            return toJwk(read(Files.readString(file, StandardCharsets.US_ASCII)));
        } catch (IOException | GeneralSecurityException | JOSEException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load the token signing key from " + file, e);
        }
    }

    /** A key that lives only in memory: tests, and a local run without a key file. */
    static RSAKey inMemory() {
        try {
            return toJwk((RSAPrivateCrtKey) newKeyPair().getPrivate());
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("Cannot generate the token signing key", e);
        }
    }

    private static void create(Path file) throws IOException, GeneralSecurityException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        String pem = BEGIN + "\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(newKeyPair().getPrivate().getEncoded())
                + "\n" + END + "\n";
        // Written aside and then linked into place, so another instance never reads half a key.
        Path temp = Files.createTempFile(dir, ".signing-key", ".tmp");
        try {
            Files.writeString(temp, pem, StandardCharsets.US_ASCII);
            restrict(temp);
            Files.createLink(file, temp);
        } catch (FileAlreadyExistsException e) {
            // Another instance created it first: use theirs.
        } catch (UnsupportedOperationException | IOException e) {
            if (!Files.exists(file)) {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system (Windows): the directory's own permissions apply.
        }
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static RSAPrivateCrtKey read(String pem) throws GeneralSecurityException {
        String body = pem.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAKey toJwk(RSAPrivateCrtKey privateKey) throws GeneralSecurityException, JOSEException {
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new RSAKey.Builder(publicKey).privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint()
                .build();
    }
}

package co.com.finova.identity.jwt;

import co.com.finova.identity.model.token.AccessTokenClaims;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The signing key survives restarts and is shared by every instance (audit finding: key generated on each start). */
class SigningKeyFileTest {

    private static final UUID USER = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final UUID SESSION = UUID.fromString("0b6f1c2e-3d4a-4b5c-8d9e-0f1a2b3c4d5e");

    @TempDir
    Path dir;

    @Test
    void aMissingFileIsCreatedOnceAsAPrivatePemAndReadBackAsTheSameKey() throws Exception {
        Path file = dir.resolve("keys/jwt-signing-key.pem");

        RSAKey created = SigningKeyFile.loadOrCreate(file);
        RSAKey reloaded = SigningKeyFile.loadOrCreate(file);

        assertThat(Files.readString(file)).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(reloaded.getKeyID()).isEqualTo(created.getKeyID()).isEqualTo(created.computeThumbprint().toString());
        assertThat(reloaded.toRSAPublicKey()).isEqualTo(created.toRSAPublicKey());
        assertThat(reloaded.isPrivate()).isTrue();
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
        try (var leftovers = Files.list(file.getParent())) {
            assertThat(leftovers).containsExactly(file);
        }
    }

    @Test
    void aTokenSignedBeforeARestartIsStillValidAfterIt() {
        Path file = dir.resolve("jwt-signing-key.pem");
        String token = new RsaTokenSigner("finova-identity", file.toString())
                .signAccessToken(USER, "web-1", SESSION, List.of("USER")).value();

        RsaTokenSigner restarted = new RsaTokenSigner("finova-identity", file.toString());

        Optional<AccessTokenClaims> claims = restarted.verifyAccessToken(token);
        assertThat(claims).map(AccessTokenClaims::userId).contains(USER);
    }

    @Test
    void instancesStartingTogetherEndUpWithOneKey() throws Exception {
        Path file = dir.resolve("jwt-signing-key.pem");
        Callable<String> start = () -> SigningKeyFile.loadOrCreate(file).getKeyID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> kids = pool.invokeAll(java.util.Collections.nCopies(8, start));
            for (Future<String> kid : kids) {
                assertThat(kid.get()).isEqualTo(SigningKeyFile.loadOrCreate(file).getKeyID());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aBrokenKeyFileStopsTheStartInsteadOfSigningWithAnotherKey() throws Exception {
        Path file = dir.resolve("jwt-signing-key.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> SigningKeyFile.loadOrCreate(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void withoutAFileEveryStartHasItsOwnKey() {
        assertThat(SigningKeyFile.inMemory().getKeyID()).isNotEqualTo(SigningKeyFile.inMemory().getKeyID());
        String token = new RsaTokenSigner("finova-identity", "")
                .signAccessToken(USER, "web-1", SESSION, List.of("USER")).value();
        assertThat(new RsaTokenSigner("finova-identity", " ").verifyAccessToken(token)).isEmpty();
        assertThat(new RsaTokenSigner("finova-identity", Clock.systemUTC()).publicKeys()).containsKey("keys");
    }
}

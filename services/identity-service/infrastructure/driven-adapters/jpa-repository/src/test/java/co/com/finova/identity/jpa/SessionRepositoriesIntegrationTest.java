package co.com.finova.identity.jpa;

import co.com.finova.identity.model.session.RefreshToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SessionRepositoriesIntegrationTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ANA = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final Instant EXPIRES = Instant.parse("2026-11-09T12:00:00Z");

    @Autowired
    private RefreshTokenJpaRepository jpa;

    @Autowired
    private UserJpaRepository userJpa;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void savedTokenIsFoundByItsHash() {
        RefreshTokenRepositoryAdapter tokens = new RefreshTokenRepositoryAdapter(jpa);
        RefreshToken token = token("a", UUID.randomUUID());

        tokens.save(token);

        assertThat(tokens.findByHash(token.tokenHash())).contains(token);
        assertThat(tokens.findByHash("f".repeat(64))).isEmpty();
    }

    @Test
    void onlyTheFirstRotationOfAnActiveTokenSucceeds() {
        RefreshTokenRepositoryAdapter tokens = new RefreshTokenRepositoryAdapter(jpa);
        RefreshToken token = token("b", UUID.randomUUID());
        tokens.save(token);

        assertThat(tokens.markRotated(token.tokenHash())).isTrue();
        assertThat(tokens.markRotated(token.tokenHash())).isFalse();
        assertThat(tokens.findByHash(token.tokenHash()).orElseThrow().status()).isEqualTo(RefreshToken.Status.ROTATED);
    }

    @Test
    void revokingAFamilyLeavesOtherSessionsAlone() {
        RefreshTokenRepositoryAdapter tokens = new RefreshTokenRepositoryAdapter(jpa);
        UUID family = UUID.randomUUID();
        RefreshToken old = token("c", family);
        RefreshToken current = token("d", family);
        RefreshToken otherSession = token("e", UUID.randomUUID());
        tokens.save(old);
        tokens.save(current);
        tokens.save(otherSession);
        tokens.markRotated(old.tokenHash());

        tokens.revokeFamily(family);

        assertThat(tokens.findByHash(old.tokenHash()).orElseThrow().status()).isEqualTo(RefreshToken.Status.REVOKED);
        assertThat(tokens.findByHash(current.tokenHash()).orElseThrow().status()).isEqualTo(RefreshToken.Status.REVOKED);
        assertThat(tokens.findByHash(otherSession.tokenHash()).orElseThrow().status()).isEqualTo(RefreshToken.Status.ACTIVE);
    }

    @Test
    void aFamilyStaysRevokedEvenForATokenSavedAfterTheRevocation() {
        RefreshTokenRepositoryAdapter tokens = new RefreshTokenRepositoryAdapter(jpa);
        UUID family = UUID.randomUUID();
        UUID otherFamily = UUID.randomUUID();
        RefreshToken rotated = token("f", family);
        tokens.save(rotated);
        tokens.save(token("g", otherFamily));
        tokens.markRotated(rotated.tokenHash());
        assertThat(tokens.isFamilyRevoked(family)).isFalse();

        tokens.revokeFamily(family);
        tokens.save(token("h", family));

        assertThat(tokens.isFamilyRevoked(family)).isTrue();
        assertThat(tokens.isFamilyRevoked(otherFamily)).isFalse();
    }

    @Test
    void deviceIsRegisteredOnceAndItsLastSeenMovesForward() {
        DeviceRepositoryAdapter devices = new DeviceRepositoryAdapter(jdbc);
        devices.recordLogin(ANA, "ios-1");
        jdbc.update("UPDATE devices SET registered_at = ?, last_seen_at = ? WHERE device_id = 'ios-1'",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));

        devices.recordLogin(ANA, "ios-1");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM devices WHERE user_id = ?", Integer.class, ANA)).isOne();
        assertThat(jdbc.queryForObject("SELECT registered_at < last_seen_at FROM devices WHERE device_id = 'ios-1'", Boolean.class))
                .isTrue();
    }

    @Test
    void userIsFoundById() {
        assertThat(new UserRepositoryAdapter(userJpa).findById(ANA).orElseThrow().email()).isEqualTo("ana@example.com");
        assertThat(new UserRepositoryAdapter(userJpa).findById(UUID.randomUUID())).isEmpty();
    }

    private static RefreshToken token(String hashChar, UUID family) {
        return new RefreshToken(hashChar.repeat(64), family, ANA, "ios-1", RefreshToken.Status.ACTIVE, EXPIRES);
    }
}

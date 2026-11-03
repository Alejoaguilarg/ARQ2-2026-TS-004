package co.com.finova.identity.jpa;

import co.com.finova.identity.model.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Real PostgreSQL: Flyway applies V1 and V2, and Hibernate validates the mapping against them.
@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserJpaRepository jpa;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void demoUsersAreSeededWithFixedIdsAndRoles() {
        UserRepositoryAdapter users = new UserRepositoryAdapter(jpa);

        User ana = users.findByEmail("ana@example.com").orElseThrow();
        User auditor = users.findByEmail("auditor@example.com").orElseThrow();

        assertThat(ana.userId()).isEqualTo(UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c"));
        assertThat(ana.status()).isEqualTo(User.Status.ACTIVE);
        assertThat(ana.roles()).containsExactly("USER");
        assertThat(ana.passwordHash()).startsWith("$argon2id$");
        assertThat(ana.pinHash()).startsWith("$argon2id$").isNotEqualTo(ana.passwordHash());
        assertThat(auditor.roles()).containsExactly("USER", "AUDITOR");
        assertThat(users.findByEmail("beto@example.com")).isPresent();
    }

    @Test
    void lookupIgnoresCaseAndSurroundingSpaces() {
        assertThat(new UserRepositoryAdapter(jpa).findByEmail("  Ana@Example.COM ")).isPresent();
    }

    @Test
    void unknownEmailFindsNothing() {
        assertThat(new UserRepositoryAdapter(jpa).findByEmail("nobody@example.com")).isEmpty();
    }

    // One failing insert per test: PostgreSQL aborts the test's transaction after the first error.
    @Test
    void schemaRejectsMixedCaseEmails() {
        assertThatThrownBy(() -> insert("Mixed@example.com", "{USER}")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaRejectsUnknownRoles() {
        assertThatThrownBy(() -> insert("eve@example.com", "{ADMIN}")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaAcceptsAValidUser() {
        insert("eve@example.com", "{USER}");

        assertThat(new UserRepositoryAdapter(jpa).findByEmail("eve@example.com")).isPresent();
    }

    @Test
    void refreshTokensOnlyAcceptKnownStates() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO refresh_tokens (token_hash, family_id, user_id, device_id, status, expires_at)
                VALUES (repeat('a', 64), gen_random_uuid(), '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c', 'd-1', 'USED', now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(String email, String roles) {
        jdbc.update("INSERT INTO users (user_id, email, status, roles, password_hash, pin_hash) "
                + "VALUES (gen_random_uuid(), ?, 'ACTIVE', ?::text[], 'x', 'x')", email, roles);
    }

    @Test
    void rolesAreReadAsAnImmutableList() {
        List<String> roles = new UserRepositoryAdapter(jpa).findByEmail("ana@example.com").orElseThrow().roles();

        assertThatThrownBy(() -> roles.add("AUDITOR")).isInstanceOf(UnsupportedOperationException.class);
    }
}

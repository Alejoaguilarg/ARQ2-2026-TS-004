package co.com.finova.identity.jpa;

import co.com.finova.identity.model.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

// Read-only mapping of V1__identity_schema.sql; Flyway owns the schema.
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    private String email;

    @Enumerated(EnumType.STRING)
    private User.Status status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] roles;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "otp_secret")
    private String otpSecret;

    User toUser() {
        return new User(userId, email, status, List.of(roles), passwordHash, pinHash, otpSecret);
    }
}

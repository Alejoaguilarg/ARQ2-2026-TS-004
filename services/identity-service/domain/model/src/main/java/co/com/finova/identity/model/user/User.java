package co.com.finova.identity.model.user;

import java.util.List;
import java.util.UUID;

// spec.md §2 User. Hashes travel with the user so the use cases can verify credentials.
public record User(UUID userId, String email, Status status, List<String> roles, String passwordHash,
                   String pinHash) {

    public enum Status { ACTIVE, LOCKED, DISABLED }

    public User {
        roles = List.copyOf(roles);
    }
}

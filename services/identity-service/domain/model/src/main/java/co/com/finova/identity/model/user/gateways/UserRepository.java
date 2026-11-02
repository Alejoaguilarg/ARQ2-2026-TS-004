package co.com.finova.identity.model.user.gateways;

import co.com.finova.identity.model.user.User;

import java.util.Optional;

public interface UserRepository {

    // Case-insensitive: "Ana@Example.com" finds ana@example.com.
    Optional<User> findByEmail(String email);
}

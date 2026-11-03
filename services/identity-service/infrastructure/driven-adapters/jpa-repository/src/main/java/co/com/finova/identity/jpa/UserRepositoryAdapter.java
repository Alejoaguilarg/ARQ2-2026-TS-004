package co.com.finova.identity.jpa;

import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository users;

    @Override
    public Optional<User> findByEmail(String email) {
        // Emails are stored lower-cased (CHECK in V1).
        return users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).map(UserEntity::toUser);
    }

    @Override
    public Optional<User> findById(UUID userId) {
        return users.findById(userId).map(UserEntity::toUser);
    }
}

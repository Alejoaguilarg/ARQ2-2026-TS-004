package co.com.finova.identity.usecase.user;

import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.UserRepository;

import java.util.Optional;
import java.util.UUID;

// spec.md §3.12: turns the email a sender typed into the recipient's userId, so the app never asks for a UUID.
// A DISABLED user is answered as unknown: nobody can send money to a closed account.
public class FindRecipientUseCase {

    private final UserRepository users;

    public FindRecipientUseCase(UserRepository users) {
        this.users = users;
    }

    public Optional<UUID> byEmail(String email) {
        return users.findByEmail(email)
                .filter(user -> user.status() != User.Status.DISABLED)
                .map(User::userId);
    }
}

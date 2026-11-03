package co.com.finova.identity.usecase.user;

import co.com.finova.identity.model.user.User;
import co.com.finova.identity.model.user.gateways.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FindRecipientUseCaseTest {

    private static final UUID BETO = UUID.fromString("2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f");

    private final UserRepository users = mock(UserRepository.class);
    private final FindRecipientUseCase recipients = new FindRecipientUseCase(users);

    @Test
    void answersTheUserIdOfTheEmail() {
        when(users.findByEmail("beto@example.com")).thenReturn(Optional.of(user(User.Status.ACTIVE)));

        assertThat(recipients.byEmail("beto@example.com")).contains(BETO);
    }

    @Test
    void aLockedUserCanStillReceive() {
        // LOCKED stops the login, not the money coming in.
        when(users.findByEmail("beto@example.com")).thenReturn(Optional.of(user(User.Status.LOCKED)));

        assertThat(recipients.byEmail("beto@example.com")).contains(BETO);
    }

    @Test
    void aDisabledUserIsAnsweredAsUnknown() {
        when(users.findByEmail("beto@example.com")).thenReturn(Optional.of(user(User.Status.DISABLED)));

        assertThat(recipients.byEmail("beto@example.com")).isEmpty();
    }

    @Test
    void anUnknownEmailIsEmpty() {
        when(users.findByEmail("nadie@example.com")).thenReturn(Optional.empty());

        assertThat(recipients.byEmail("nadie@example.com")).isEmpty();
    }

    private static User user(User.Status status) {
        return new User(BETO, "beto@example.com", status, List.of("USER"), "pw-hash", "pin-hash", null);
    }
}

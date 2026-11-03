package co.com.finova.identity.api;

import co.com.finova.identity.usecase.user.FindRecipientUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** spec.md §3.12: the email a sender typed becomes the recipient's userId. */
class UserControllerTest {

    private static final UUID BETO = UUID.fromString("2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f");

    private final FindRecipientUseCase recipients = mock(FindRecipientUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserController(recipients)).build();

    @Test
    void answersTheUserIdOfTheEmail() throws Exception {
        when(recipients.byEmail("Beto@Example.com ")).thenReturn(Optional.of(BETO));

        lookup("{\"email\":\"Beto@Example.com \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(BETO.toString()))
                // Only the id: the email is what the sender already typed, and nothing else about the user leaks.
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void anUnknownEmailIs404WithTheCorrelationId() throws Exception {
        when(recipients.byEmail(any())).thenReturn(Optional.empty());

        lookup("{\"email\":\"nadie@example.com\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("c-1"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void invalidBodiesAre400WithoutTouchingTheUseCase() throws Exception {
        for (String body : new String[]{"{\"email\":\"not-an-email\"}", "{}", "not json",
                "{\"email\":\"" + "a".repeat(250) + "@example.com\"}"}) {
            lookup(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(recipients);
    }

    @Test
    void anUnexpectedFailureIs500() throws Exception {
        when(recipients.byEmail(any())).thenThrow(new IllegalStateException("db down"));

        lookup("{\"email\":\"beto@example.com\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private ResultActions lookup(String body) throws Exception {
        return mvc.perform(post("/users/lookup").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Correlation-Id", "c-1"));
    }
}

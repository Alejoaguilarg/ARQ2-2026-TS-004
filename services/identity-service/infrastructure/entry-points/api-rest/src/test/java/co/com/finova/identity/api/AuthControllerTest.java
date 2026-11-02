package co.com.finova.identity.api;

import co.com.finova.identity.model.session.SecurityStoreUnavailableException;
import co.com.finova.identity.model.session.TokenPair;
import co.com.finova.identity.usecase.session.LoginUseCase;
import co.com.finova.identity.usecase.session.LogoutUseCase;
import co.com.finova.identity.usecase.session.RefreshSessionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private static final String LOGIN = "{\"email\":\"ana@example.com\",\"password\":\"Finova2026!\",\"deviceId\":\"ios-7F3A9C\"}";
    private static final String REFRESH = "{\"refreshToken\":\"rt_abc\",\"deviceId\":\"ios-7F3A9C\"}";

    private final LoginUseCase login = mock(LoginUseCase.class);
    private final RefreshSessionUseCase refresh = mock(RefreshSessionUseCase.class);
    private final LogoutUseCase logout = mock(LogoutUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(login, refresh, logout)).build();

    @Test
    void f11_loginAnswersTheTokenPair() throws Exception {
        when(login.login("ana@example.com", "Finova2026!", "ios-7F3A9C")).thenReturn(new LoginUseCase.Result.Issued(pair()));

        call("/auth/login", LOGIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("at"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").value("rt_new"))
                .andExpect(jsonPath("$.refreshExpiresIn").value(604800));
    }

    @Test
    void wrongCredentialsAnswerTheCodeTheGatewayCountsForTheLockout() throws Exception {
        when(login.login(any(), any(), any())).thenReturn(new LoginUseCase.Result.InvalidCredentials());

        call("/auth/login", LOGIN)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.correlationId").value("c-1"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void lockedAccountIs423() throws Exception {
        when(login.login(any(), any(), any())).thenReturn(new LoginUseCase.Result.Locked());

        call("/auth/login", LOGIN).andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void invalidLoginBodiesAre400WithoutTouchingTheUseCase() throws Exception {
        for (String body : new String[]{
                "{\"email\":\"not-an-email\",\"password\":\"x\",\"deviceId\":\"d\"}",
                "{\"email\":\"ana@example.com\",\"password\":\"\",\"deviceId\":\"d\"}",
                "{\"email\":\"ana@example.com\",\"password\":\"x\"}",
                "{\"email\":\"ana@example.com\",\"password\":\"x\",\"deviceId\":\"" + "d".repeat(129) + "\"}",
                "not json"}) {
            call("/auth/login", body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(login);
    }

    @Test
    void deviceIdOf128CharactersIsAccepted() throws Exception {
        when(login.login(any(), any(), any())).thenReturn(new LoginUseCase.Result.InvalidCredentials());

        call("/auth/login", "{\"email\":\"ana@example.com\",\"password\":\"x\",\"deviceId\":\"" + "d".repeat(128) + "\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void f12_refreshAnswersTheNewPair() throws Exception {
        when(refresh.refresh("rt_abc", "ios-7F3A9C")).thenReturn(new RefreshSessionUseCase.Result.Issued(pair()));

        call("/auth/refresh", REFRESH).andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").value("rt_new"));
    }

    @Test
    void invalidRefreshTokenIs401Invalid() throws Exception {
        when(refresh.refresh(any(), any())).thenReturn(new RefreshSessionUseCase.Result.Invalid());

        call("/auth/refresh", REFRESH).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void f13_reusedRefreshTokenIs401Reused() throws Exception {
        when(refresh.refresh(any(), any())).thenReturn(new RefreshSessionUseCase.Result.Reused());

        call("/auth/refresh", REFRESH).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void refreshWithoutTokenIs400() throws Exception {
        call("/auth/refresh", "{\"deviceId\":\"d\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(refresh);
    }

    @Test
    void redisDownIs503() throws Exception {
        when(refresh.refresh(any(), any())).thenThrow(new SecurityStoreUnavailableException(new RuntimeException()));

        call("/auth/refresh", REFRESH).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECURITY_STORE_UNAVAILABLE"));
    }

    @Test
    void unexpectedFailureIs500WithoutDetails() throws Exception {
        when(login.login(any(), any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));

        call("/auth/login", LOGIN).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error."));
    }

    @Test
    void logoutWithAValidTokenIs204() throws Exception {
        when(logout.logout("at-1")).thenReturn(true);

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer at-1")).andExpect(status().isNoContent());
    }

    @Test
    void logoutWithAnInvalidTokenIs401() throws Exception {
        when(logout.logout("forged")).thenReturn(false);

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutWithoutABearerIs401WithoutTouchingTheUseCase() throws Exception {
        mvc.perform(post("/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout").header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isUnauthorized());
        verifyNoInteractions(logout);
    }

    @Test
    void logoutWithRedisDownIs503() throws Exception {
        when(logout.logout("at-1")).thenThrow(new SecurityStoreUnavailableException(new RuntimeException()));

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer at-1"))
                .andExpect(status().isServiceUnavailable());
    }

    private ResultActions call(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-Correlation-Id", "c-1").content(body));
    }

    private static TokenPair pair() {
        return new TokenPair("at", "rt_new");
    }
}

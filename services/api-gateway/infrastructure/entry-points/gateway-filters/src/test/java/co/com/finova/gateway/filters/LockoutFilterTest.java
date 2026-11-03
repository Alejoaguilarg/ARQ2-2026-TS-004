package co.com.finova.gateway.filters;

import co.com.finova.gateway.usecase.security.LockoutUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LockoutFilterTest {

    private static final String LOGIN = "{\"email\":\"ana@example.com\",\"password\":\"x\",\"deviceId\":\"ios-1\"}";

    private final InMemoryCounterStore counters = new InMemoryCounterStore();
    private final LockoutFilter filter = new LockoutFilter(new LockoutUseCase(counters));

    private int identityCalls;
    private String forwardedBody;
    private int identityStatus;
    private String identityCode;
    private final FilterChain identity = (request, response) -> {
        identityCalls++;
        forwardedBody = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ((HttpServletResponse) response).setStatus(identityStatus);
        response.getWriter().write("{\"code\":\"" + identityCode + "\"}");
    };

    @Test
    void fiveWrongPasswordsLockTheAccountWithoutReachingIdentity() throws Exception {
        answer(401, "INVALID_CREDENTIALS");
        for (int i = 0; i < 5; i++) {
            assertThat(send(login(LOGIN)).getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse locked = send(login(LOGIN));

        assertThat(locked.getStatus()).isEqualTo(423);
        assertThat(locked.getContentAsString()).contains("ACCOUNT_LOCKED");
        assertThat(identityCalls).isEqualTo(5);
        assertThat(counters.values).containsKeys("fail:login:ana@example.com", "lock:login:ana@example.com");
    }

    @Test
    void loginBodyStillReachesIdentity() throws Exception {
        answer(200, "OK");

        send(login(LOGIN));

        assertThat(forwardedBody).isEqualTo(LOGIN);
    }

    @Test
    void successfulLoginClearsTheFailureCounter() throws Exception {
        answer(401, "INVALID_CREDENTIALS");
        send(login(LOGIN));
        answer(200, "OK");

        send(login(LOGIN));

        assertThat(counters.values).doesNotContainKey("fail:login:ana@example.com");
    }

    @Test
    void emailCaseDoesNotBypassTheLock() throws Exception {
        answer(401, "INVALID_CREDENTIALS");
        for (int i = 0; i < 5; i++) {
            send(login(LOGIN));
        }

        assertThat(send(login(LOGIN.replace("ana@example.com", " Ana@Example.COM "))).getStatus()).isEqualTo(423);
    }

    @Test
    void otherLoginErrorsDoNotCount() throws Exception {
        answer(400, "VALIDATION_ERROR");
        send(login(LOGIN));
        answer(401, "TOKEN_REVOKED");
        send(login(LOGIN));

        assertThat(counters.values).isEmpty();
    }

    @Test
    void fiveFailedStepUpsLockStepUp() throws Exception {
        answer(401, "STEP_UP_FAILED");
        for (int i = 0; i < 5; i++) {
            send(stepUp("u1"));
        }

        MockHttpServletResponse locked = send(stepUp("u1"));

        assertThat(locked.getStatus()).isEqualTo(423);
        assertThat(locked.getContentAsString()).contains("STEP_UP_LOCKED");
        assertThat(counters.values).containsKey("lock:stepup:u1");
    }

    @Test
    void successfulStepUpClearsTheCounter() throws Exception {
        answer(401, "STEP_UP_FAILED");
        send(stepUp("u1"));
        answer(201, "OK");

        send(stepUp("u1"));

        assertThat(counters.values).doesNotContainKey("fail:stepup:u1");
    }

    @Test
    void redisDownFailsClosed() throws Exception {
        counters.down = true;

        MockHttpServletResponse response = send(login(LOGIN));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SECURITY_STORE_UNAVAILABLE");
        assertThat(identityCalls).isZero();
    }

    @Test
    void loginWithoutEmailIsPassedToIdentityUncounted() throws Exception {
        answer(400, "VALIDATION_ERROR");

        assertThat(send(login("{\"password\":\"x\"}")).getStatus()).isEqualTo(400);
        assertThat(identityCalls).isOne();
    }

    @Test
    void otherRoutesAreNotFiltered() throws Exception {
        answer(200, "OK");

        filter.doFilter(new MockHttpServletRequest("POST", "/auth/refresh"), new MockHttpServletResponse(), identity);

        assertThat(counters.values).isEmpty();
        assertThat(identityCalls).isOne();
    }

    private void answer(int status, String code) {
        identityStatus = status;
        identityCode = code;
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, identity);
        return response;
    }

    private static MockHttpServletRequest login(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest stepUp(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/financial-session");
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, userId);
        request.setContent("{\"method\":\"PIN\",\"credential\":\"0000\"}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
}

package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.usecase.security.SessionUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationFilterTest {

    private static final AccessToken USER = new AccessToken("jti-1", "u-1", "d-1", "sid-1", List.of("USER"));
    private static final AccessToken AUDITOR = new AccessToken("jti-2", "u-2", "d-2", "sid-2", List.of("USER", "AUDITOR"));
    private static final String CORRELATION_ID = "0b6f1c2e-3d4a-4b5c-8d9e-0f1a2b3c4d5e";

    private final FakeSessions fake = new FakeSessions();
    private final AuthenticationFilter filter = new AuthenticationFilter(new SessionUseCase(fake, fake));

    private HttpServletRequest routed;
    private final FilterChain upstream = (request, response) -> routed = (HttpServletRequest) request;

    AuthenticationFilterTest() {
        fake.accessTokens.put("user-token", USER);
        fake.accessTokens.put("auditor-token", AUDITOR);
    }

    @Test
    void validTokenIsRoutedWithTheIdentityHeadersFromTheToken() throws Exception {
        MockHttpServletRequest request = get("/wallets/me/balance", "Bearer user-token");
        request.addHeader("X-User-Id", "someone-else");

        assertThat(send(request).getStatus()).isEqualTo(200);
        assertThat(routed.getHeader("X-User-Id")).isEqualTo("u-1");
        assertThat(Collections.list(routed.getHeaders("x-user-id"))).containsExactly("u-1");
        assertThat(routed.getHeader("X-Device-Id")).isEqualTo("d-1");
        assertThat(routed.getHeader("X-User-Roles")).isEqualTo("USER");
        assertThat(routed.getAttribute(AuthenticatedUser.ATTRIBUTE)).isEqualTo("u-1");
        assertThat(routed.getAttribute(AuthenticatedUser.TOKEN_ATTRIBUTE)).isEqualTo(USER);
        assertThat(Collections.list(routed.getHeaderNames()))
                .filteredOn(name -> name.equalsIgnoreCase("X-User-Id")).hasSize(1);
    }

    @Test
    void missingTokenIs401AndNotRouted() throws Exception {
        MockHttpServletResponse response = send(get("/wallets/me/balance", null));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"UNAUTHORIZED\"");
        assertThat(routed).isNull();
    }

    @Test
    void otherAuthorizationSchemeIs401() throws Exception {
        assertThat(send(get("/wallets/me/balance", "Basic dXNlcjpwYXNz")).getStatus()).isEqualTo(401);
        assertThat(routed).isNull();
    }

    @Test
    void schemeIsCaseInsensitive() throws Exception {
        assertThat(send(get("/wallets/me/balance", "bearer user-token")).getStatus()).isEqualTo(200);
    }

    @Test
    void invalidOrExpiredTokenIs401() throws Exception {
        fake.expired.add("old-token");

        assertThat(send(get("/wallets/me/balance", "Bearer forged")).getStatus()).isEqualTo(401);
        assertThat(send(get("/wallets/me/balance", "Bearer old-token")).getStatus()).isEqualTo(401);
        assertThat(routed).isNull();
    }

    @Test
    void f14_revokedSessionIs401TokenRevoked() throws Exception {
        fake.revoked.add("sid-1");

        MockHttpServletResponse response = send(get("/wallets/me/balance", "Bearer user-token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_REVOKED");
        assertThat(routed).isNull();
    }

    @Test
    void auditRoutesRequireTheAuditorRole() throws Exception {
        MockHttpServletResponse denied = send(get("/audit/records", "Bearer user-token"));

        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentAsString()).contains("FORBIDDEN_ROLE");
        assertThat(routed).isNull();

        assertThat(send(get("/audit/records", "Bearer auditor-token")).getStatus()).isEqualTo(200);
        assertThat(routed.getHeader("X-User-Roles")).isEqualTo("USER,AUDITOR");
    }

    @Test
    void f112_storeDownIs503AndNotRouted() throws Exception {
        fake.down = true;

        MockHttpServletResponse balance = send(get("/wallets/me/balance", "Bearer user-token"));
        MockHttpServletRequest transfer = get("/transfers", "Bearer user-token");
        transfer.setMethod("POST");
        MockHttpServletResponse transferResponse = send(transfer);

        assertThat(balance.getStatus()).isEqualTo(503);
        assertThat(balance.getContentAsString()).contains("SECURITY_STORE_UNAVAILABLE");
        assertThat(transferResponse.getStatus()).isEqualTo(503);
        assertThat(transferResponse.getContentAsString()).contains("IDEMPOTENCY_STORE_UNAVAILABLE");
        assertThat(routed).isNull();
    }

    @Test
    void loginAndRefreshNeedNoTokenButStillLoseClientIdentityHeaders() throws Exception {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/auth/login");
        login.addHeader("X-User-Id", "u-1");
        login.addHeader("X-User-Roles", "AUDITOR");

        assertThat(send(login).getStatus()).isEqualTo(200);
        assertThat(routed.getHeader("X-User-Id")).isNull();
        assertThat(routed.getHeader("X-User-Roles")).isNull();
        assertThat(routed.getHeaders("X-User-Id").hasMoreElements()).isFalse();

        assertThat(send(new MockHttpServletRequest("POST", "/auth/refresh")).getStatus()).isEqualTo(200);
    }

    @Test
    void otherHeadersPassThroughUntouched() throws Exception {
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/auth/login");
        login.addHeader("Content-Type", "application/json");
        login.addHeader("X-Device-Id", "spoofed");

        send(login);

        assertThat(routed.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(Collections.list(routed.getHeaders("Content-Type"))).containsExactly("application/json");
        assertThat(Collections.list(routed.getHeaderNames()))
                .contains("Content-Type", "X-Correlation-Id")
                .noneMatch(name -> name.equalsIgnoreCase("X-Device-Id"));
    }

    @Test
    void logoutAndGetOnLoginPathStillNeedAToken() throws Exception {
        assertThat(send(new MockHttpServletRequest("POST", "/auth/logout")).getStatus()).isEqualTo(401);
        assertThat(send(new MockHttpServletRequest("GET", "/auth/login")).getStatus()).isEqualTo(401);
    }

    @Test
    void actuatorNeedsATokenOnThePublicPort() throws Exception {
        // Health and metrics live on the management port; here /actuator is not a way around authentication.
        assertThat(send(new MockHttpServletRequest("GET", "/actuator/prometheus")).getStatus()).isEqualTo(401);
    }

    @Test
    void correlationIdIsAlwaysGeneratedByTheGateway() throws Exception {
        MockHttpServletRequest request = get("/wallets/me/balance", "Bearer user-token");
        request.addHeader("X-Correlation-Id", CORRELATION_ID);

        send(request);

        assertThat(routed.getHeader("X-Correlation-Id")).matches("[0-9a-f-]{36}").isNotEqualTo(CORRELATION_ID);
        assertThat(Collections.list(routed.getHeaders("X-Correlation-Id"))).hasSize(1);
    }

    @Test
    void errorBodiesCarryTheGatewayCorrelationId() throws Exception {
        assertThat(send(get("/wallets/me/balance", null)).getContentAsString())
                .containsPattern("\"correlationId\":\"[0-9a-f-]{36}\"");
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, upstream);
        return response;
    }

    private static MockHttpServletRequest get(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}

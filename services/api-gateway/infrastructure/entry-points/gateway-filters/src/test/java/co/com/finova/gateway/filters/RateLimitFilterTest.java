package co.com.finova.gateway.filters;

import co.com.finova.gateway.usecase.security.RateLimitUseCase;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final InMemoryCounterStore counters = new InMemoryCounterStore();
    private final RateLimitFilter filter = new RateLimitFilter(new RateLimitUseCase(counters));

    private int upstreamCalls;
    private final FilterChain upstream = (request, response) -> upstreamCalls++;

    @Test
    void transfersAllow30PerMinutePerUserThen429WithRetryAfter() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(send(userRequest("POST", "/transfers", "u1")).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse limited = send(userRequest("POST", "/transfers", "u1"));

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(limited.getHeader("Retry-After")).isEqualTo("42");
        assertThat(upstreamCalls).isEqualTo(30);
        assertThat(counters.values).containsKey("rl:transfers:u1");
    }

    @Test
    void limitsAreCountedPerUser() throws Exception {
        for (int i = 0; i < 31; i++) {
            send(userRequest("POST", "/transfers", "u1"));
        }

        assertThat(send(userRequest("POST", "/transfers", "u2")).getStatus()).isEqualTo(200);
    }

    @Test
    void loginAndRefreshShareTenPerMinutePerClientIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            send(ipRequest("/auth/login", "10.0.0.1"));
            send(ipRequest("/auth/refresh", "10.0.0.1"));
        }

        assertThat(send(ipRequest("/auth/login", "10.0.0.1")).getStatus()).isEqualTo(429);
        assertThat(send(ipRequest("/auth/login", "10.0.0.2")).getStatus()).isEqualTo(200);
        assertThat(counters.values).containsKey("rl:auth:10.0.0.1");
    }

    @Test
    void clientForwardedForHeaderIsIgnored() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = ipRequest("/auth/login", "10.0.0.1");
            request.addHeader("X-Forwarded-For", "203.0.113." + i);
            send(request);
        }

        assertThat(send(ipRequest("/auth/login", "10.0.0.1")).getStatus()).isEqualTo(429);
    }

    @Test
    void balanceAndTransferStatusAllow120() throws Exception {
        for (int i = 0; i < 120; i++) {
            send(userRequest("GET", "/wallets/me/balance", "u1"));
            send(userRequest("GET", "/transfers/9b2f6c1e", "u1"));
        }

        assertThat(send(userRequest("GET", "/wallets/me/balance", "u1")).getStatus()).isEqualTo(429);
        assertThat(send(userRequest("GET", "/transfers/9b2f6c1e", "u1")).getStatus()).isEqualTo(429);
    }

    @Test
    void routesWithoutARuleAreNotCounted() throws Exception {
        send(userRequest("GET", "/audit/records", "u1"));

        assertThat(upstreamCalls).isOne();
        assertThat(counters.values).isEmpty();
    }

    @Test
    void redisDownFailsClosedWithTheRouteCode() throws Exception {
        counters.down = true;

        MockHttpServletResponse transfer = send(userRequest("POST", "/transfers", "u1"));
        MockHttpServletResponse balance = send(userRequest("GET", "/wallets/me/balance", "u1"));

        assertThat(transfer.getStatus()).isEqualTo(503);
        assertThat(transfer.getContentAsString()).contains("IDEMPOTENCY_STORE_UNAVAILABLE");
        assertThat(balance.getContentAsString()).contains("SECURITY_STORE_UNAVAILABLE");
        assertThat(upstreamCalls).isZero();
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, upstream);
        return response;
    }

    private static MockHttpServletRequest userRequest(String method, String path, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, userId);
        return request;
    }

    private static MockHttpServletRequest ipRequest(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }
}

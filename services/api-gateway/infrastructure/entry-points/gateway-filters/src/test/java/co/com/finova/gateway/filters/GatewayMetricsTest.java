package co.com.finova.gateway.filters;

import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

// In the app Spring Boot adds the Prometheus registry to Metrics.globalRegistry; here a simple one stands in.
class GatewayMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void register() {
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void unregister() {
        Metrics.removeRegistry(registry);
    }

    @Test
    void everyRejectionIsCountedByCodeAndStatus() throws Exception {
        GatewayResponses.error(new MockHttpServletRequest(), new MockHttpServletResponse(), 401, "TOKEN_REVOKED", "m");
        GatewayResponses.error(new MockHttpServletRequest(), new MockHttpServletResponse(), 401, "TOKEN_REVOKED", "m");
        GatewayResponses.storeUnavailable(new MockHttpServletRequest("GET", "/wallets/me/balance"), new MockHttpServletResponse());

        assertThat(rejections("TOKEN_REVOKED", "401")).isEqualTo(2);
        assertThat(rejections("SECURITY_STORE_UNAVAILABLE", "503")).isEqualTo(1);
    }

    @Test
    void aReplayedTransferIsCountedOnlyWhenTheStoredAnswerIsReturned() throws Exception {
        IdempotencyFilter filter = new IdempotencyFilter(new IdempotencyUseCase(new IdempotencyFilterTest.InMemoryStore()));
        String key = "3f1e9a7c-5b2d-4c8e-9f0a-1b2c3d4e5f60";

        filter.doFilter(transfer(key), new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(202));
        // Registered at 0 on start, so the first replay is a visible increase (see IdempotencyFilter).
        assertThat(registry.get(IdempotencyFilter.REPLAYS).counter().count()).isZero();

        filter.doFilter(transfer(key), new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(202));
        assertThat(registry.get(IdempotencyFilter.REPLAYS).counter().count()).isEqualTo(1);
    }

    private double rejections(String code, String status) {
        return registry.get(GatewayResponses.REJECTIONS).tag("code", code).tag("status", status).counter().count();
    }

    private static MockHttpServletRequest transfer(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers");
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
        request.addHeader("Idempotency-Key", key);
        request.setContent("{\"amount\":\"1.00\"}".getBytes());
        return request;
    }
}

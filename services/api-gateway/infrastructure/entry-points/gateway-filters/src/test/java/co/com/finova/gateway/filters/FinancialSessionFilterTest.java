package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.FinancialSessionToken;
import co.com.finova.gateway.usecase.security.SessionUseCase;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialSessionFilterTest {

    private static final AccessToken USER = new AccessToken("jti-1", "u-1", "d-1", "sid-1", List.of("USER"));
    private static final String K1 = "3f1e9a7c-5b2d-4c8e-9f0a-1b2c3d4e5f60";
    private static final String K2 = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

    private final FakeSessions fake = new FakeSessions();
    private final FinancialSessionFilter filter = new FinancialSessionFilter(new SessionUseCase(fake, fake));

    private int upstreamCalls;
    private final FilterChain upstream = (request, response) -> upstreamCalls++;

    FinancialSessionFilterTest() {
        fake.financialSessions.put("fs-ok", new FinancialSessionToken("fs-1", "u-1", "d-1", "transfer:create"));
        fake.financialSessions.put("fs-other-device", new FinancialSessionToken("fs-2", "u-1", "d-9", "transfer:create"));
    }

    @Test
    void validSessionIsRoutedAndClaimedWithTheKey() throws Exception {
        assertThat(send(transfer("fs-ok", K1)).getStatus()).isEqualTo(200);
        assertThat(upstreamCalls).isOne();
        assertThat(fake.used).containsEntry("fs-1", K1);
    }

    @Test
    void f17_missingSessionIs403Required() throws Exception {
        MockHttpServletResponse response = send(transfer(null, K1));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FINANCIAL_SESSION_REQUIRED");
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void f18_sessionOfAnotherDeviceIs403Invalid() throws Exception {
        MockHttpServletResponse response = send(transfer("fs-other-device", K1));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FINANCIAL_SESSION_INVALID");
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void f18_forgedSessionIs403Invalid() throws Exception {
        assertThat(send(transfer("forged", K1)).getContentAsString()).contains("FINANCIAL_SESSION_INVALID");
    }

    @Test
    void f19_expiredSessionIs403Expired() throws Exception {
        fake.expired.add("fs-old");

        MockHttpServletResponse response = send(transfer("fs-old", K1));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FINANCIAL_SESSION_EXPIRED");
    }

    @Test
    void f110_sessionUsedWithAnotherKeyIs403Used() throws Exception {
        send(transfer("fs-ok", K1));

        MockHttpServletResponse response = send(transfer("fs-ok", K2));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FINANCIAL_SESSION_USED");
        assertThat(upstreamCalls).isOne();
    }

    @Test
    void f111_retryWithTheSameKeyPassesToTheIdempotencyCheck() throws Exception {
        send(transfer("fs-ok", K1));

        assertThat(send(transfer("fs-ok", K1.toUpperCase())).getStatus()).isEqualTo(200);
        assertThat(upstreamCalls).isEqualTo(2);
    }

    @Test
    void badIdempotencyKeyIsLeftToStep4WithoutBurningTheSession() throws Exception {
        send(transfer("fs-ok", "not-a-uuid"));

        assertThat(upstreamCalls).isOne();
        assertThat(fake.used).isEmpty();
    }

    @Test
    void f112_storeDownIs503AndNotRouted() throws Exception {
        fake.down = true;

        MockHttpServletResponse response = send(transfer("fs-ok", K1));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_STORE_UNAVAILABLE");
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        MockHttpServletRequest request = transfer("fs-ok", K1);
        request.removeAttribute(AuthenticatedUser.TOKEN_ATTRIBUTE);

        assertThat(send(request).getStatus()).isEqualTo(401);
        assertThat(upstreamCalls).isZero();
    }

    @Test
    void otherRoutesAreNotFiltered() throws Exception {
        send(new MockHttpServletRequest("GET", "/transfers/t-1"));

        assertThat(upstreamCalls).isOne();
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, upstream);
        return response;
    }

    private static MockHttpServletRequest transfer(String session, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers");
        request.setAttribute(AuthenticatedUser.TOKEN_ATTRIBUTE, USER);
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, USER.userId());
        if (session != null) {
            request.addHeader("X-Financial-Session", session);
        }
        request.addHeader("Idempotency-Key", key);
        return request;
    }
}

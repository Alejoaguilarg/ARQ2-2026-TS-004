package co.com.finova.gateway.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private static final String CLIENT_ID = "0b6f1c2e-3d4a-4b5c-8d9e-0f1a2b3c4d5e";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void theRestOfTheRequestSeesOneIdInTheLogsTheRequestAndTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wallets/me/balance");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> logged = new AtomicReference<>();
        AtomicReference<String> forRequest = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            logged.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            forRequest.set(CorrelationIdFilter.of(req));
        });

        assertThat(logged.get()).matches("[0-9a-f-]{36}");
        assertThat(forRequest.get()).isEqualTo(logged.get());
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(logged.get());
    }

    @Test
    void theClientCannotChooseTheId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wallets/me/balance");
        request.addHeader("X-Correlation-Id", CLIENT_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Correlation-Id")).isNotEqualTo(CLIENT_ID);
    }

    @Test
    void eachRequestGetsItsOwnId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> { });

        assertThat(first.getHeader("X-Correlation-Id")).isNotEqualTo(second.getHeader("X-Correlation-Id"));
    }

    @Test
    void theIdIsRemovedFromTheThreadAfterwardsEvenOnError() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void withoutTheFilterAnIdIsStillProvided() {
        assertThat(CorrelationIdFilter.of(new MockHttpServletRequest())).matches("[0-9a-f-]{36}");
    }
}

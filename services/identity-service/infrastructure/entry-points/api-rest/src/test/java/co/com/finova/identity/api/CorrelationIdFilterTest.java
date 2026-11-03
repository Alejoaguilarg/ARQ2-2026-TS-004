package co.com.finova.identity.api;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private static final String ID = "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void theGatewayIdIsInTheLogsWhileTheRequestRuns() throws Exception {
        assertThat(loggedDuring(ID)).isEqualTo(ID);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void anythingButAUuidIsNotWrittenToTheLogs() throws Exception {
        assertThat(loggedDuring(null)).isNull();
        assertThat(loggedDuring("not-a-uuid")).isNull();
        assertThat(loggedDuring(ID + " INFO forged line")).isNull();
    }

    @Test
    void theIdIsRemovedEvenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", ID);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private String loggedDuring(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader("X-Correlation-Id", header);
        }
        AtomicReference<String> logged = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> logged.set(MDC.get(CorrelationIdFilter.MDC_KEY)));
        return logged.get();
    }
}

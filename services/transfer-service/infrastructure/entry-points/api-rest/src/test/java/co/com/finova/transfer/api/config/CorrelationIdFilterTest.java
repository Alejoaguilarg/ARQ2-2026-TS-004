package co.com.finova.transfer.api.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Spec §1.4: the correlationId the Gateway forwards goes in the MDC, so every log line of the request carries it.
class CorrelationIdFilterTest {

    private static final String ID = "5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> seenByChain = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> seenByChain.set(MDC.get("correlationId"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void uuidHeaderIsInTheMdcDuringTheRequestAndRemovedAfter() throws Exception {
        filter.doFilter(requestWith(ID), response, chain);

        assertThat(seenByChain.get()).isEqualTo(ID);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void headerThatIsNotAUuidIsIgnored() throws Exception {
        filter.doFilter(requestWith("forged\nline"), response, chain);

        assertThat(seenByChain.get()).isNull();
    }

    @Test
    void missingHeaderLeavesTheMdcEmpty() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(seenByChain.get()).isNull();
    }

    @Test
    void mdcIsClearedWhenTheRequestFails() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(requestWith(ID), response, failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("correlationId")).isNull();
    }

    private static MockHttpServletRequest requestWith(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", correlationId);
        return request;
    }
}

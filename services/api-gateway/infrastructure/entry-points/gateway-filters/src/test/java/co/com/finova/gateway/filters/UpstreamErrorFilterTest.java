package co.com.finova.gateway.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamErrorFilterTest {

    private final UpstreamErrorFilter filter = new UpstreamErrorFilter();

    @Test
    void refusedConnectionIs502() throws Exception {
        MockHttpServletResponse response = send(failing(new ConnectException("Connection refused")));

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).contains("\"UPSTREAM_UNAVAILABLE\"");
    }

    @Test
    void jdkClientTimeoutIs504() throws Exception {
        assertThat(send(failing(new HttpTimeoutException("request timed out"))).getStatus()).isEqualTo(504);
    }

    @Test
    void socketTimeoutIs504() throws Exception {
        assertThat(send(failing(new SocketTimeoutException("Read timed out"))).getStatus()).isEqualTo(504);
    }

    @Test
    void errorBodyCarriesTheCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wallets/me/balance");
        request.addHeader("X-Correlation-Id", "c-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, failing(new ConnectException()));

        assertThat(response.getContentAsString()).contains("\"correlationId\":\"c-1\"");
    }

    @Test
    void otherErrorsAreLeftAlone() {
        FilterChain chain = (request, response) -> {
            throw new ServletException(new IllegalStateException("bug"));
        };

        assertThatThrownBy(() -> send(chain)).isInstanceOf(ServletException.class);
    }

    @Test
    void anAnswerAlreadyStartedIsNotRewritten() {
        FilterChain chain = (request, response) -> {
            response.getOutputStream().write('{');
            response.flushBuffer();
            throw new ResourceAccessException("cut", new IOException("reset"));
        };

        assertThatThrownBy(() -> send(chain)).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void bytesBufferedBeforeTheFailureAreDiscarded() throws Exception {
        MockHttpServletResponse response = send((request, resp) -> {
            resp.getOutputStream().write("partial".getBytes());
            throw new ResourceAccessException("cut", new IOException("reset"));
        });

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).startsWith("{\"code\":\"UPSTREAM_UNAVAILABLE\"");
    }

    @Test
    void successfulAnswersPassThrough() throws Exception {
        MockHttpServletResponse response = send((request, resp) -> ((jakarta.servlet.http.HttpServletResponse) resp).setStatus(202));

        assertThat(response.getStatus()).isEqualTo(202);
    }

    // DispatcherServlet wraps the RestClient error in a ServletException.
    private static FilterChain failing(IOException cause) {
        return (request, response) -> {
            throw new ServletException("Request processing failed", new ResourceAccessException("I/O error", cause));
        };
    }

    private MockHttpServletResponse send(FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/wallets/me/balance"), response, chain);
        return response;
    }
}

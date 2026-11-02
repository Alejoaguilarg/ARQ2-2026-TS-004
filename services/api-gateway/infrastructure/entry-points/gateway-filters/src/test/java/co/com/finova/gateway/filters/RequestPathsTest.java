package co.com.finova.gateway.filters;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPathsTest {

    @Test
    void usesTheContainerNormalizedPathInsteadOfTheRawUri() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers;x=1");
        request.setServletPath("/transfers");

        assertThat(RequestPaths.of(request)).isEqualTo("/transfers");
    }

    @Test
    void appendsPathInfo() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/audit/records");
        request.setServletPath("/audit");
        request.setPathInfo("/records");

        assertThat(RequestPaths.of(request)).isEqualTo("/audit/records");
    }

    @Test
    void dropsATrailingSlash() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers/");
        request.setServletPath("/transfers/");

        assertThat(RequestPaths.of(request)).isEqualTo("/transfers");
    }

    @Test
    void keepsTheRoot() {
        assertThat(RequestPaths.of(new MockHttpServletRequest("GET", "/"))).isEqualTo("/");
    }

    @Test
    void fallsBackToTheUriWhenNoServletPathIsSet() {
        assertThat(RequestPaths.of(new MockHttpServletRequest("GET", "/wallets/me/balance"))).isEqualTo("/wallets/me/balance");
    }
}

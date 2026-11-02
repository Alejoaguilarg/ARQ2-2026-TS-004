package co.com.finova.gateway.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

// spec.md §3.1 step 5: an upstream that is down answers 502, one that times out answers 504, both
// UPSTREAM_UNAVAILABLE. Runs last so IdempotencyFilter sees the 5xx and frees the key (§3.1.1).
@Component
@Order(UpstreamErrorFilter.ORDER)
public class UpstreamErrorFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

    private static final System.Logger LOG = System.getLogger(UpstreamErrorFilter.class.getName());

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            ResourceAccessException upstream = find(e, ResourceAccessException.class);
            if (upstream == null || response.isCommitted()) {
                throw e;
            }
            boolean timeout = find(upstream, HttpTimeoutException.class) != null
                    || find(upstream, SocketTimeoutException.class) != null;
            LOG.log(System.Logger.Level.WARNING, "Upstream unavailable for " + RequestPaths.of(request), upstream);
            response.resetBuffer();
            GatewayResponses.error(request, response, timeout ? 504 : 502, "UPSTREAM_UNAVAILABLE",
                    "The service is temporarily unavailable.");
        }
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}

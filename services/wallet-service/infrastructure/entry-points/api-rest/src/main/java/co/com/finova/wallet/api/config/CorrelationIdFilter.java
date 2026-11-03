package co.com.finova.wallet.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spec §1.4: the correlationId goes in every log line. The Gateway creates it and forwards it in
 * X-Correlation-Id (§1.5); it is put in the MDC for the whole request. Only a UUID is accepted, so a direct
 * call cannot write arbitrary text into the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (UuidFormat.isUuid(id)) {
            MDC.put(MDC_KEY, id);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // The server reuses the thread for the next request.
            MDC.remove(MDC_KEY);
        }
    }
}

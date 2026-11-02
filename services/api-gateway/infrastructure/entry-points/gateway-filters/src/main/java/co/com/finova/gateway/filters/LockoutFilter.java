package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.usecase.security.LockoutUseCase;
import co.com.finova.gateway.usecase.security.LockoutUseCase.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Locale;

// spec.md §3.1.2 lockout: counts Identity's answers and blocks the route while lock:* exists.
@Component
@Order(LockoutFilter.ORDER)
@RequiredArgsConstructor
public class LockoutFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    private static final System.Logger LOG = System.getLogger(LockoutFilter.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LockoutUseCase lockout;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
                && ("/auth/login".equals(RequestPaths.of(request)) || "/auth/financial-session".equals(RequestPaths.of(request))));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean login = "/auth/login".equals(RequestPaths.of(request));
        Kind kind = login ? Kind.LOGIN : Kind.STEP_UP;
        HttpServletRequest forwarded = request;
        String subject;
        if (login) {
            byte[] body = CachedBodyRequest.readLimited(request);
            if (body.length > CachedBodyRequest.MAX_BODY_BYTES) {
                GatewayResponses.error(request, response, 413, "PAYLOAD_TOO_LARGE", "Request body is too large.");
                return;
            }
            forwarded = new CachedBodyRequest(request, body);
            subject = email(body);
        } else {
            Object userId = request.getAttribute(AuthenticatedUser.ATTRIBUTE);
            subject = userId == null ? null : userId.toString();
        }
        if (subject == null) {
            // No email (Identity answers 400) or no user (authentication rejects it): nothing to count.
            chain.doFilter(forwarded, response);
            return;
        }

        try {
            if (lockout.isLocked(kind, subject)) {
                GatewayResponses.error(request, response, 423, login ? "ACCOUNT_LOCKED" : "STEP_UP_LOCKED",
                        "Too many failed attempts; try again later.");
                return;
            }
        } catch (SecurityStoreUnavailableException e) {
            LOG.log(System.Logger.Level.ERROR, "Security store unavailable; failing closed", e);
            GatewayResponses.error(request, response, 503, "SECURITY_STORE_UNAVAILABLE", "Service temporarily unavailable.");
            return;
        }

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        chain.doFilter(forwarded, captured);
        try {
            if (captured.getStatus() == 401 && (login ? "INVALID_CREDENTIALS" : "STEP_UP_FAILED").equals(code(captured))) {
                lockout.failed(kind, subject);
            } else if (captured.getStatus() == (login ? 200 : 201)) {
                lockout.succeeded(kind, subject);
            }
        } catch (SecurityStoreUnavailableException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not update lockout counter", e);
        }
        captured.copyBodyToResponse();
    }

    // Lower-cased so "Ana@Example.com" and "ana@example.com" share one counter and one lock.
    private static String email(byte[] body) {
        try {
            JsonNode email = JSON.readTree(body).get("email");
            return email == null || !email.isTextual() || email.asText().isBlank()
                    ? null : email.asText().trim().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return null;
        }
    }

    private static String code(ContentCachingResponseWrapper response) {
        try {
            JsonNode code = JSON.readTree(response.getContentAsByteArray()).get("code");
            return code == null ? null : code.asText();
        } catch (IOException e) {
            return null;
        }
    }
}

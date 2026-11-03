package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.usecase.security.RateLimitUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

// spec.md §3.1 step 2 and §3.1.2 rate limits.
@Component
@Order(RateLimitFilter.ORDER)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private record Rule(String method, Pattern path, String scope, int limitPerMinute, boolean perClientIp) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("POST", Pattern.compile("/auth/(login|refresh)"), "auth", 10, true),
            new Rule("POST", Pattern.compile("/auth/financial-session"), "financial-session", 10, false),
            new Rule("POST", Pattern.compile("/transfers"), "transfers", 30, false),
            new Rule("GET", Pattern.compile("/wallets/me/balance"), "balance", 120, false),
            new Rule("GET", Pattern.compile("/transfers/[^/]+"), "transfer-status", 120, false),
            // Low on purpose: each call answers whether an email has an account (spec.md §3.12).
            new Rule("POST", Pattern.compile("/users/lookup"), "user-lookup", 30, false));

    private static final System.Logger LOG = System.getLogger(RateLimitFilter.class.getName());

    private final RateLimitUseCase rateLimit;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Rule> rule = RULES.stream()
                .filter(r -> r.method().equals(request.getMethod()) && r.path().matcher(RequestPaths.of(request)).matches())
                .findFirst();
        // The Gateway is the edge, so the socket address is the client; X-Forwarded-For would be client-controlled.
        Object subject = rule.isEmpty() ? null
                : rule.get().perClientIp() ? request.getRemoteAddr() : request.getAttribute(AuthenticatedUser.ATTRIBUTE);
        if (subject == null) {
            // No rule, or a user route without a user: authentication rejects it.
            chain.doFilter(request, response);
            return;
        }

        Optional<Duration> retryAfter;
        try {
            retryAfter = rateLimit.check(rule.get().scope(), subject.toString(), rule.get().limitPerMinute());
        } catch (SecurityStoreUnavailableException e) {
            LOG.log(System.Logger.Level.ERROR, "Security store unavailable; failing closed", e);
            GatewayResponses.storeUnavailable(request, response);
            return;
        }
        if (retryAfter.isPresent()) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, (retryAfter.get().toMillis() + 999) / 1000)));
            GatewayResponses.error(request, response, 429, "RATE_LIMIT_EXCEEDED", "Too many requests.");
            return;
        }
        chain.doFilter(request, response);
    }
}

package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.usecase.security.SessionUseCase;
import co.com.finova.gateway.usecase.security.SessionUseCase.FinancialSessionCheck;
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
import java.util.Locale;

// spec.md §3.1 step 3 on POST /transfers: X-Financial-Session, single use per transfer intent.
@Component
@Order(FinancialSessionFilter.ORDER)
@RequiredArgsConstructor
public class FinancialSessionFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 35;

    private static final System.Logger LOG = System.getLogger(FinancialSessionFilter.class.getName());

    private final SessionUseCase sessions;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/transfers".equals(RequestPaths.of(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(request.getAttribute(AuthenticatedUser.TOKEN_ATTRIBUTE) instanceof AccessToken user)) {
            GatewayResponses.error(request, response, 401, "UNAUTHORIZED", "Authentication required.");
            return;
        }
        String session = request.getHeader("X-Financial-Session");
        if (session == null || session.isBlank()) {
            GatewayResponses.error(request, response, 403, "FINANCIAL_SESSION_REQUIRED",
                    "X-Financial-Session header is required.");
            return;
        }
        String key = request.getHeader("Idempotency-Key");
        if (!IdempotencyFilter.isValidKey(key)) {
            // Claiming the session with a bad key would burn it; step 4 answers 400.
            chain.doFilter(request, response);
            return;
        }

        FinancialSessionCheck check;
        try {
            check = sessions.checkFinancialSession(session.trim(), user, key.toLowerCase(Locale.ROOT));
        } catch (SecurityStoreUnavailableException e) {
            LOG.log(System.Logger.Level.ERROR, "Security store unavailable; failing closed", e);
            GatewayResponses.storeUnavailable(request, response);
            return;
        }
        switch (check) {
            case ACCEPTED -> chain.doFilter(request, response);
            case INVALID -> GatewayResponses.error(request, response, 403, "FINANCIAL_SESSION_INVALID",
                    "Financial session is not valid for this user, device or operation.");
            case EXPIRED -> GatewayResponses.error(request, response, 403, "FINANCIAL_SESSION_EXPIRED",
                    "Financial session has expired.");
            case USED -> GatewayResponses.error(request, response, 403, "FINANCIAL_SESSION_USED",
                    "Financial session was already used by another transfer.");
        }
    }
}

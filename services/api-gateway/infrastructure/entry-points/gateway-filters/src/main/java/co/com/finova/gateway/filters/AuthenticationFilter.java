package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.security.AccessToken;
import co.com.finova.gateway.model.security.SecurityStoreUnavailableException;
import co.com.finova.gateway.usecase.security.SessionUseCase;
import co.com.finova.gateway.usecase.security.SessionUseCase.Authentication;
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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

// spec.md §3.1 step 1 (JWT, revocation, AUDITOR role) and the header handling of step 5.
// Runs first after CorrelationIdFilter so every later step and every error body sees the user.
@Component
@Order(AuthenticationFilter.ORDER)
@RequiredArgsConstructor
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Pattern BEARER = Pattern.compile("^Bearer +(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final System.Logger LOG = System.getLogger(AuthenticationFilter.class.getName());

    private final SessionUseCase sessions;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> headers = new HashMap<>();
        // spec.md §1.5: the Gateway owns the correlation id; a client-sent one is replaced.
        headers.put(CorrelationIdFilter.HEADER, CorrelationIdFilter.of(request));

        if (isPublic(request)) {
            chain.doFilter(new IdentityHeadersRequest(request, headers), response);
            return;
        }
        HttpServletRequest forwarded = new IdentityHeadersRequest(request, headers);

        String authorization = request.getHeader("Authorization");
        var bearer = authorization == null ? null : BEARER.matcher(authorization.trim());
        if (bearer == null || !bearer.matches()) {
            GatewayResponses.error(forwarded, response, 401, "UNAUTHORIZED", "Authentication required.");
            return;
        }

        Authentication result;
        try {
            result = sessions.authenticate(bearer.group(1));
        } catch (SecurityStoreUnavailableException e) {
            LOG.log(System.Logger.Level.ERROR, "Security store unavailable; failing closed", e);
            GatewayResponses.storeUnavailable(forwarded, response);
            return;
        }
        AccessToken token;
        switch (result) {
            case Authentication.Unauthorized u -> {
                GatewayResponses.error(forwarded, response, 401, "UNAUTHORIZED", "Invalid or expired access token.");
                return;
            }
            case Authentication.Revoked r -> {
                GatewayResponses.error(forwarded, response, 401, "TOKEN_REVOKED", "Access token has been revoked.");
                return;
            }
            case Authentication.Authenticated a -> token = a.token();
        }
        String path = RequestPaths.of(request);
        if ((path.equals("/audit") || path.startsWith("/audit/")) && !token.hasRole("AUDITOR")) {
            GatewayResponses.error(forwarded, response, 403, "FORBIDDEN_ROLE", "Role AUDITOR required.");
            return;
        }

        headers.put("X-User-Id", token.userId());
        headers.put("X-Device-Id", token.deviceId());
        headers.put("X-User-Roles", String.join(",", token.roles()));
        forwarded = new IdentityHeadersRequest(request, headers);
        forwarded.setAttribute(AuthenticatedUser.ATTRIBUTE, token.userId());
        forwarded.setAttribute(AuthenticatedUser.TOKEN_ATTRIBUTE, token);
        chain.doFilter(forwarded, response);
    }

    // spec.md §3.1.3: only login and refresh go without a Bearer; health and metrics stay internal.
    private static boolean isPublic(HttpServletRequest request) {
        String path = RequestPaths.of(request);
        return "POST".equals(request.getMethod()) && ("/auth/login".equals(path) || "/auth/refresh".equals(path))
                || path.startsWith("/actuator/");
    }
}

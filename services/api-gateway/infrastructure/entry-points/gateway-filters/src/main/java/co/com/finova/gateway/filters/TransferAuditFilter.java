package co.com.finova.gateway.filters;

import co.com.finova.gateway.usecase.audit.RecordAttemptUseCase;
import co.com.finova.gateway.usecase.audit.RecordAttemptUseCase.Answer;
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

import java.io.IOException;
import java.util.regex.Pattern;

// spec.md §3.1 step 6: every POST /transfers rejected in steps 1-4, answered as a duplicate, or failed upstream is
// audited. Wraps every other check (only CorrelationIdFilter runs before it) and records once the answer is known.
@Component
@Order(TransferAuditFilter.ORDER)
@RequiredArgsConstructor
public class TransferAuditFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    private static final System.Logger LOG = System.getLogger(TransferAuditFilter.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RecordAttemptUseCase recordAttempt;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/transfers".equals(RequestPaths.of(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            // Nothing upstream turned it into an answer: the client gets a 500.
            record(request, 500);
            throw e;
        }
        record(request, response.getStatus());
    }

    private void record(HttpServletRequest request, int status) {
        try {
            String key = request.getHeader("Idempotency-Key");
            Object replayed = request.getAttribute(IdempotencyFilter.REPLAY_ATTRIBUTE);
            Object userId = request.getAttribute(AuthenticatedUser.ATTRIBUTE);
            recordAttempt.record(new Answer(status,
                    (String) request.getAttribute(GatewayResponses.CODE_ATTRIBUTE),
                    replayed != null,
                    replayed != null ? transferId(replayed.toString()) : null,
                    userId != null ? userId.toString() : null,
                    IdempotencyFilter.isValidKey(key) ? key.toLowerCase() : null,
                    CorrelationIdFilter.of(request)));
        } catch (RuntimeException e) {
            // The audit is never in the critical path: the client still gets its answer.
            LOG.log(System.Logger.Level.ERROR, "Could not record the transfer attempt", e);
        }
    }

    private static String transferId(String body) {
        try {
            String id = JSON.readTree(body).path("transferId").asText(null);
            return id != null && UUID_PATTERN.matcher(id).matches() ? id : null;
        } catch (IOException e) {
            return null;
        }
    }
}

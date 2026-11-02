package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.idempotency.IdempotencyStoreUnavailableException;
import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase;
import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase.Decision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

// spec.md §3.1 step 4 and §3.1.1 on POST /transfers.
@Component
@Order(IdempotencyFilter.ORDER)
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 40;

    private static final System.Logger LOG = System.getLogger(IdempotencyFilter.class.getName());
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final ObjectMapper CANONICAL_JSON =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final IdempotencyUseCase idempotency;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/transfers".equals(RequestPaths.of(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object userId = request.getAttribute(AuthenticatedUser.ATTRIBUTE);
        if (userId == null) {
            GatewayResponses.error(request, response, 401, "UNAUTHORIZED", "Authentication required.");
            return;
        }
        String key = request.getHeader("Idempotency-Key");
        if (!isValidKey(key)) {
            GatewayResponses.error(request, response, 400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header must be a UUID.");
            return;
        }
        key = key.toLowerCase();
        byte[] body = CachedBodyRequest.readLimited(request);
        if (body.length > CachedBodyRequest.MAX_BODY_BYTES) {
            GatewayResponses.error(request, response, 413, "PAYLOAD_TOO_LARGE", "Request body is too large.");
            return;
        }
        String requestHash = sha256(canonicalJson(body));

        Decision decision;
        try {
            decision = idempotency.begin(userId.toString(), key, requestHash);
        } catch (IdempotencyStoreUnavailableException e) {
            LOG.log(System.Logger.Level.ERROR, "Idempotency store unavailable; failing closed", e);
            GatewayResponses.error(request, response, 503, "IDEMPOTENCY_STORE_UNAVAILABLE", "Transfers are temporarily unavailable.");
            return;
        }
        switch (decision) {
            case Decision.Proceed p -> route(request, response, chain, userId.toString(), key, requestHash, body);
            case Decision.InProgress p -> GatewayResponses.error(request, response, 409, "IDEMPOTENCY_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed.");
            case Decision.KeyReused p -> GatewayResponses.error(request, response, 422, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request body.");
            case Decision.Replay replay -> {
                response.setStatus(replay.httpStatus());
                response.setContentType("application/json");
                response.setHeader("Idempotent-Replayed", "true");
                response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static boolean isValidKey(String key) {
        return key != null && UUID_PATTERN.matcher(key).matches();
    }

    private void route(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                       String userId, String key, String requestHash, byte[] body) throws IOException, ServletException {
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CachedBodyRequest(request, body), captured);
        } catch (IOException | ServletException | RuntimeException e) {
            storeQuietly(() -> idempotency.abort(userId, key));
            throw e;
        }
        String answer = new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8);
        storeQuietly(() -> idempotency.finish(userId, key, requestHash, captured.getStatus(), answer));
        captured.copyBodyToResponse();
    }

    // The upstream already answered: losing Redis now only leaves the key IN_PROGRESS until its TTL (spec F3.9).
    private static void storeQuietly(Runnable store) {
        try {
            store.run();
        } catch (IdempotencyStoreUnavailableException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not update idempotency record", e);
        }
    }

    private static byte[] canonicalJson(byte[] body) {
        try {
            return CANONICAL_JSON.writeValueAsBytes(CANONICAL_JSON.readValue(body, Object.class));
        } catch (IOException e) {
            // Not JSON: Transfer will reject it; hashing the raw bytes still matches the client's retries.
            return body;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

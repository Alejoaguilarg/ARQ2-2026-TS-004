package co.com.finova.gateway.filters;

import co.com.finova.gateway.model.idempotency.IdempotencyStoreUnavailableException;
import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase;
import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase.Decision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

// spec.md §3.1 step 4 and §3.1.1 on POST /transfers.
@Component
@Order(IdempotencyFilter.ORDER)
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 40;
    // Set by the authentication filter (§3.1 step 1) from the token's sub.
    public static final String USER_ID_ATTRIBUTE = "finova.userId";

    private static final System.Logger LOG = System.getLogger(IdempotencyFilter.class.getName());
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    // A transfer body is a few hundred bytes; the limit keeps an untrusted client from filling the Gateway's memory.
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final ObjectMapper CANONICAL_JSON =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final IdempotencyUseCase idempotency;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/transfers".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object userId = request.getAttribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            error(request, response, 401, "UNAUTHORIZED", "Authentication required.");
            return;
        }
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !UUID_PATTERN.matcher(key).matches()) {
            error(request, response, 400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header must be a UUID.");
            return;
        }
        key = key.toLowerCase();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            error(request, response, 413, "PAYLOAD_TOO_LARGE", "Request body is too large.");
            return;
        }
        String requestHash = sha256(canonicalJson(body));

        Decision decision;
        try {
            decision = idempotency.begin(userId.toString(), key, requestHash);
        } catch (IdempotencyStoreUnavailableException e) {
            LOG.log(System.Logger.Level.ERROR, "Idempotency store unavailable; failing closed", e);
            error(request, response, 503, "IDEMPOTENCY_STORE_UNAVAILABLE", "Transfers are temporarily unavailable.");
            return;
        }
        switch (decision) {
            case Decision.Proceed p -> route(request, response, chain, userId.toString(), key, requestHash, body);
            case Decision.InProgress p -> error(request, response, 409, "IDEMPOTENCY_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed.");
            case Decision.KeyReused p -> error(request, response, 422, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request body.");
            case Decision.Replay replay -> {
                response.setStatus(replay.httpStatus());
                response.setContentType("application/json");
                response.setHeader("Idempotent-Replayed", "true");
                response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
            }
        }
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

    private static void error(HttpServletRequest request, HttpServletResponse response, int status, String code,
                              String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("correlationId", request.getHeader("X-Correlation-Id"));
        error.put("timestamp", Instant.now().toString());
        error.put("details", Map.of());
        response.setStatus(status);
        response.setContentType("application/json");
        CANONICAL_JSON.writeValue(response.getOutputStream(), error);
    }

    // Lets the proxy read the body again after the filter consumed it for the hash.
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}

package co.com.finova.gateway.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class GatewayResponses {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GatewayResponses() {
    }

    // ErrorResponse, spec.md §3.2.
    static void error(HttpServletRequest request, HttpServletResponse response, int status, String code,
                      String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("correlationId", request.getHeader("X-Correlation-Id"));
        error.put("timestamp", Instant.now().toString());
        error.put("details", Map.of());
        response.setStatus(status);
        response.setContentType("application/json");
        JSON.writeValue(response.getOutputStream(), error);
    }

    // spec.md §3.1 "Redis unavailable": fail closed; transfers keep the idempotency code (ADR 003).
    static void storeUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean transfer = "POST".equals(request.getMethod()) && "/transfers".equals(RequestPaths.of(request));
        error(request, response, 503, transfer ? "IDEMPOTENCY_STORE_UNAVAILABLE" : "SECURITY_STORE_UNAVAILABLE",
                "Service temporarily unavailable.");
    }
}

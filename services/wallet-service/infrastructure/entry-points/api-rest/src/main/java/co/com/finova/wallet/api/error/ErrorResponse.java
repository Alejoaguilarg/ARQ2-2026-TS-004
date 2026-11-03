package co.com.finova.wallet.api.error;

import java.util.Map;

/** Every error response (spec §3.2). correlationId is null when the request carried no X-Correlation-Id. */
public record ErrorResponse(String code, String message, String correlationId, String timestamp,
                            Map<String, Object> details) {
}

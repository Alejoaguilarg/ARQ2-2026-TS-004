package co.com.finova.audit.api.error;

import co.com.finova.audit.api.AuditController;
import co.com.finova.audit.model.query.InvalidAuditQueryException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.util.Map;

/** Maps errors to ErrorResponse (spec §3.2). Internal details never reach the body, only the log. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String INTERNAL_MESSAGE = "Unexpected error.";

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ForbiddenRoleException.class)
    public ResponseEntity<ErrorResponse> forbiddenRole(ForbiddenRoleException e, HttpServletRequest request) {
        LOG.warn("{} correlationId={}", e.getMessage(), correlationId(request));
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "Role AUDITOR required.", Map.of(), request);
    }

    @ExceptionHandler(InvalidAuditQueryException.class)
    public ResponseEntity<ErrorResponse> invalidQuery(InvalidAuditQueryException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid query parameter: " + e.getMessage() + ".",
                Map.of("field", e.field(), "reason", e.reason()), request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.error("unexpected error on {} correlationId={}", request.getRequestURI(), correlationId(request), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_MESSAGE, Map.of(), request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
                                                Map<String, Object> details, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(code, message, correlationId(request), clock.instant().toString(),
                details);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String correlationId(HttpServletRequest request) {
        return request.getHeader(AuditController.CORRELATION_ID_HEADER);
    }
}

package co.com.finova.transfer.api.error;

import co.com.finova.transfer.api.config.CorrelationIdFilter;
import co.com.finova.transfer.model.transfer.IdempotencyKeyReusedException;
import co.com.finova.transfer.model.transfer.SelfTransferNotAllowedException;
import co.com.finova.transfer.model.transfer.TransferNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request body.", e.details());
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> missingKey(MissingIdempotencyKeyException e) {
        return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header must be a UUID.",
                Map.of());
    }

    @ExceptionHandler(SelfTransferNotAllowedException.class)
    public ResponseEntity<ErrorResponse> selfTransfer(SelfTransferNotAllowedException e) {
        return error(HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED", "The recipient must differ from the sender.",
                Map.of());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> keyReused(IdempotencyKeyReusedException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with a different request body.", Map.of());
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(TransferNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found.", Map.of());
    }

    @ExceptionHandler(InvalidIdentityHeaderException.class)
    public ResponseEntity<ErrorResponse> invalidIdentityHeader(InvalidIdentityHeaderException e) {
        LOG.error("{}; the Gateway must inject it (spec §1.5)", e.getMessage());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_MESSAGE, Map.of());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.error("unexpected error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_MESSAGE, Map.of());
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
                                                Map<String, Object> details) {
        ErrorResponse body = new ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY),
                clock.instant().toString(), details);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}

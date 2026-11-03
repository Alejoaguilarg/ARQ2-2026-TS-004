package co.com.finova.wallet.api.error;

import co.com.finova.wallet.api.balance.BalanceController;
import co.com.finova.wallet.model.wallet.WalletNotFoundException;
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

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> walletNotFound(WalletNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "User has no wallet.", request);
    }

    @ExceptionHandler(InvalidIdentityHeaderException.class)
    public ResponseEntity<ErrorResponse> invalidIdentityHeader(InvalidIdentityHeaderException e,
                                                               HttpServletRequest request) {
        LOG.error("{}; the Gateway must inject it (spec §1.5) correlationId={}", e.getMessage(),
                correlationId(request));
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_MESSAGE, request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.error("unexpected error on {} correlationId={}", request.getRequestURI(), correlationId(request), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_MESSAGE, request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
                                                HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(code, message, correlationId(request), clock.instant().toString(),
                Map.of());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String correlationId(HttpServletRequest request) {
        return request.getHeader(BalanceController.CORRELATION_ID_HEADER);
    }
}

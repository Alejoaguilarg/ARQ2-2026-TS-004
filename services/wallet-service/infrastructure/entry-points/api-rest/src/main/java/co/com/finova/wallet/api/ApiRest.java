package co.com.finova.wallet.api;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.WalletNotFoundException;
import co.com.finova.wallet.usecase.balance.GetBalanceUseCase;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ApiRest {

    private static final System.Logger LOG = System.getLogger(ApiRest.class.getName());

    private final GetBalanceUseCase getBalance;

    @GetMapping(path = "/wallets/me/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    public BalanceResponse balance(@RequestHeader("X-User-Id") UUID userId) {
        return BalanceResponse.from(getBalance.execute(userId));
    }

    // X-User-Id is set by the Gateway from the token; missing or malformed means the request did not come through it.
    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> unauthorized(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid user identity.", request);
    }

    @ExceptionHandler(WalletNotFoundException.class)
    ResponseEntity<ErrorResponse> walletNotFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "The user has no wallet.", request);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ErrorResponse> internalError(RuntimeException e, HttpServletRequest request) {
        LOG.log(System.Logger.Level.ERROR, "Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.", request);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(code, message, request.getHeader("X-Correlation-Id"), Instant.now().toString(), Map.of()));
    }

    public record BalanceResponse(String walletId, String available, String currency, long version, String asOf) {
        static BalanceResponse from(Balance b) {
            return new BalanceResponse(b.walletId().toString(), b.available().setScale(2).toPlainString(),
                    b.currency(), b.version(), b.asOf().toString());
        }
    }

    public record ErrorResponse(String code, String message, String correlationId, String timestamp,
                                Map<String, Object> details) {
    }
}

package co.com.finova.identity.api;

import co.com.finova.identity.model.session.RefreshToken;
import co.com.finova.identity.model.session.SecurityStoreUnavailableException;
import co.com.finova.identity.model.session.TokenPair;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.usecase.session.LoginUseCase;
import co.com.finova.identity.usecase.session.LogoutUseCase;
import co.com.finova.identity.usecase.session.RefreshSessionUseCase;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

// spec.md §3.3 to §3.5. The Gateway counts the 401 INVALID_CREDENTIALS answers for the lockout,
// so that code must stay exactly as it is.
@RestController
@RequiredArgsConstructor
public class AuthController {

    private static final Pattern BEARER = Pattern.compile("^Bearer +(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_DEVICE_ID = 128;
    private static final System.Logger LOG = System.getLogger(AuthController.class.getName());

    private final LoginUseCase login;
    private final RefreshSessionUseCase refresh;
    private final LogoutUseCase logout;

    public record LoginBody(String email, String password, String deviceId) {
    }

    public record RefreshBody(String refreshToken, String deviceId) {
    }

    public record TokenPairBody(String accessToken, String tokenType, long expiresIn, String refreshToken,
                                long refreshExpiresIn) {
        // The lifetimes, as in the spec examples (900 / 604800), not a countdown.
        static TokenPairBody from(TokenPair pair) {
            return new TokenPairBody(pair.accessToken(), "Bearer", TokenSigner.ACCESS_TOKEN_TTL.toSeconds(),
                    pair.refreshToken(), RefreshToken.TTL.toSeconds());
        }
    }

    public record ErrorBody(String code, String message, String correlationId, String timestamp,
                            Map<String, Object> details) {
    }

    @PostMapping(path = "/auth/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> login(@RequestBody LoginBody body, HttpServletRequest request) {
        if (body == null || isBlank(body.password()) || body.email() == null || !EMAIL.matcher(body.email().trim()).matches()
                || !validDevice(body.deviceId())) {
            return validationError(request, "email, password and deviceId (at most 128 characters) are required.");
        }
        return switch (login.login(body.email(), body.password(), body.deviceId())) {
            case LoginUseCase.Result.Issued issued -> ResponseEntity.ok(TokenPairBody.from(issued.tokens()));
            case LoginUseCase.Result.InvalidCredentials ignored ->
                    error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.", request);
            case LoginUseCase.Result.Locked ignored ->
                    error(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "The account is locked.", request);
        };
    }

    @PostMapping(path = "/auth/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> refresh(@RequestBody RefreshBody body, HttpServletRequest request) {
        if (body == null || isBlank(body.refreshToken()) || !validDevice(body.deviceId())) {
            return validationError(request, "refreshToken and deviceId (at most 128 characters) are required.");
        }
        return switch (refresh.refresh(body.refreshToken(), body.deviceId())) {
            case RefreshSessionUseCase.Result.Issued issued -> ResponseEntity.ok(TokenPairBody.from(issued.tokens()));
            case RefreshSessionUseCase.Result.Invalid ignored -> error(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID",
                    "Refresh token is unknown, expired, revoked or bound to another device.", request);
            case RefreshSessionUseCase.Result.Reused ignored -> error(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                    "Refresh token was already used; the session has been revoked.", request);
        };
    }

    // The Gateway already checked the token; Identity verifies it again because it needs jti, sid and exp.
    @PostMapping(path = "/auth/logout")
    public ResponseEntity<Object> logout(@RequestHeader(name = "Authorization", required = false) String authorization,
                                         HttpServletRequest request) {
        var bearer = authorization == null ? null : BEARER.matcher(authorization.trim());
        if (bearer == null || !bearer.matches() || !logout.logout(bearer.group(1))) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired access token.", request);
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Object> unreadableBody(HttpServletRequest request) {
        return validationError(request, "Request body must be a JSON object.");
    }

    @ExceptionHandler(SecurityStoreUnavailableException.class)
    ResponseEntity<Object> storeUnavailable(SecurityStoreUnavailableException e, HttpServletRequest request) {
        LOG.log(System.Logger.Level.ERROR, "Security store unavailable", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SECURITY_STORE_UNAVAILABLE", "Service temporarily unavailable.", request);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Object> internalError(RuntimeException e, HttpServletRequest request) {
        LOG.log(System.Logger.Level.ERROR, "Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.", request);
    }

    private static boolean validDevice(String deviceId) {
        return !isBlank(deviceId) && deviceId.length() <= MAX_DEVICE_ID;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseEntity<Object> validationError(HttpServletRequest request, String message) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(code, message, request.getHeader("X-Correlation-Id"), Instant.now().toString(), Map.of()));
    }
}

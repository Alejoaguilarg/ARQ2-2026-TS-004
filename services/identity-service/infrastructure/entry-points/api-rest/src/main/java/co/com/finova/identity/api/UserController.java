package co.com.finova.identity.api;

import co.com.finova.identity.usecase.user.FindRecipientUseCase;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// spec.md §3.12. POST with the email in the body, not GET with it in the query: URLs end up in access logs
// and proxies, and the email is personal data. The email is never logged. The Gateway rate-limits this route
// per user so it cannot be used to walk through the user base.
@RestController
@RequiredArgsConstructor
public class UserController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL = 254;
    private static final System.Logger LOG = System.getLogger(UserController.class.getName());

    private final FindRecipientUseCase recipients;

    public record LookupBody(String email) {
    }

    public record Recipient(UUID userId) {
    }

    @PostMapping(path = "/users/lookup", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> lookup(@RequestBody LookupBody body, HttpServletRequest request) {
        if (body == null || body.email() == null || body.email().length() > MAX_EMAIL
                || !EMAIL.matcher(body.email().trim()).matches()) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A valid email is required.", request);
        }
        return recipients.byEmail(body.email())
                .<ResponseEntity<Object>>map(userId -> ResponseEntity.ok(new Recipient(userId)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No user with that email.", request));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Object> unreadableBody(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request body must be a JSON object.", request);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Object> internalError(RuntimeException e, HttpServletRequest request) {
        LOG.log(System.Logger.Level.ERROR, "Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.", request);
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new AuthController.ErrorBody(code, message, request.getHeader("X-Correlation-Id"),
                        Instant.now().toString(), Map.of()));
    }
}

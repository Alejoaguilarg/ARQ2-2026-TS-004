package co.com.finova.wallet.api.balance;

import co.com.finova.wallet.api.error.InvalidIdentityHeaderException;
import co.com.finova.wallet.usecase.getbalance.GetBalanceUseCase;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * GET /wallets/me/balance (spec §3.7). The user comes only from X-User-Id, which the Gateway strips
 * from the client request and injects (§1.5). Both headers are optional here so that a missing or
 * invalid X-User-Id is answered as 500 INTERNAL_ERROR, a Gateway fault (decision of 2026-11-02).
 */
@RestController
public class BalanceController {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final GetBalanceUseCase getBalance;

    public BalanceController(GetBalanceUseCase getBalance) {
        this.getBalance = getBalance;
    }

    @GetMapping(path = "/wallets/me/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    public BalanceResponse balance(@RequestHeader(name = USER_ID_HEADER, required = false) String userId,
                                   @RequestHeader(name = CORRELATION_ID_HEADER, required = false) String correlationId) {
        return BalanceResponse.from(getBalance.handle(parseUserId(userId), correlationId));
    }

    private static UUID parseUserId(String header) {
        if (header == null || !UUID_FORMAT.matcher(header).matches()) {
            throw new InvalidIdentityHeaderException(USER_ID_HEADER, header);
        }
        return UUID.fromString(header);
    }
}

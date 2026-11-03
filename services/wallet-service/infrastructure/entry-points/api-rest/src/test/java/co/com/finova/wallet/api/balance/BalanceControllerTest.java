package co.com.finova.wallet.api.balance;

import co.com.finova.wallet.api.error.ApiExceptionHandler;
import co.com.finova.wallet.model.balance.BalanceSnapshot;
import co.com.finova.wallet.model.common.Currency;
import co.com.finova.wallet.model.money.Money;
import co.com.finova.wallet.model.wallet.WalletNotFoundException;
import co.com.finova.wallet.usecase.getbalance.GetBalanceUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /wallets/me/balance (spec §3.7) and its errors (spec §3.2), with the controller and the error handler
 * only: the use case is a mock, so no Spring context, Redis or PostgreSQL is needed.
 */
class BalanceControllerTest {

    private static final String PATH = "/wallets/me/balance";
    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");
    private static final UUID USER_ID = UUID.fromString("f1e2d3c4-b5a6-4789-8a0b-1c2d3e4f5a6b");
    private static final UUID WALLET_ID = UUID.fromString("d2e46189-3076-4f3d-a4f2-cd84ee25e49c");
    private static final String CORRELATION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private final GetBalanceUseCase getBalance = mock(GetBalanceUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BalanceController(getBalance))
            .setControllerAdvice(new ApiExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
            .build();

    @Test
    void returnsTheBalanceOfTheUserInTheHeader() throws Exception {
        when(getBalance.handle(USER_ID, CORRELATION_ID)).thenReturn(new BalanceSnapshot(WALLET_ID, USER_ID,
                Money.parse("350000.00"), Currency.COP, 4, Instant.parse("2026-11-02T14:59:59.123456Z")));

        mvc.perform(get(PATH).header("X-User-Id", USER_ID.toString()).header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.walletId").value(WALLET_ID.toString()))
                .andExpect(jsonPath("$.available").value("350000.00"))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.asOf").value("2026-11-02T14:59:59.123456Z"));
    }

    @Test
    void answers404WalletNotFoundWithErrorResponse() throws Exception {
        when(getBalance.handle(eq(USER_ID), any())).thenThrow(new WalletNotFoundException(USER_ID));

        mvc.perform(get(PATH).header("X-User-Id", USER_ID.toString()).header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User has no wallet."))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(jsonPath("$.timestamp").value("2026-11-02T15:00:00Z"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    // Pending 21 in .claude/wallet-plan.md, not the spec: §3.7 does not list a missing or invalid X-User-Id.
    // The Gateway must always inject it (§1.5), so the Wallet treats its absence as an internal fault.

    @Test
    void Pending21_missingUserIdHeaderAnswers500InternalError() throws Exception {
        mvc.perform(get(PATH).header("X-Correlation-Id", CORRELATION_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error."))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
        verifyNoInteractions(getBalance);
    }

    @Test
    void Pending21_userIdHeaderThatIsNotAUuidAnswers500InternalError() throws Exception {
        mvc.perform(get(PATH).header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").doesNotExist());
        verifyNoInteractions(getBalance);
    }

    @Test
    void unexpectedErrorAnswers500WithoutInternalDetails() throws Exception {
        when(getBalance.handle(eq(USER_ID), any())).thenThrow(new IllegalStateException("wallet_db is down"));

        mvc.perform(get(PATH).header("X-User-Id", USER_ID.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("wallet_db"))));
    }
}

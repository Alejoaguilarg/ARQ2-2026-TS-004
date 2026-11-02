package co.com.finova.wallet.api;

import co.com.finova.wallet.model.balance.Balance;
import co.com.finova.wallet.model.balance.WalletNotFoundException;
import co.com.finova.wallet.usecase.balance.GetBalanceUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiRestTest {

    private static final UUID USER = UUID.fromString("7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c");
    private static final UUID WALLET = UUID.fromString("0c7a0d2e-3f0b-4b8e-9a7c-2d1e5f6a7b8c");

    private final GetBalanceUseCase getBalance = mock(GetBalanceUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiRest(getBalance)).build();

    @Test
    void returnsTheBalanceInTheSpecFormat() throws Exception {
        when(getBalance.execute(USER)).thenReturn(new Balance(WALLET, USER, new BigDecimal("850000"), "COP", 42,
                Instant.parse("2026-10-19T15:00:00.120Z")));

        mvc.perform(get("/wallets/me/balance").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(WALLET.toString()))
                .andExpect(jsonPath("$.available").value("850000.00"))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andExpect(jsonPath("$.version").value(42))
                .andExpect(jsonPath("$.asOf").value("2026-10-19T15:00:00.120Z"));
    }

    @Test
    void unknownWalletIs404() throws Exception {
        when(getBalance.execute(USER)).thenThrow(new WalletNotFoundException(USER));

        mvc.perform(get("/wallets/me/balance").header("X-User-Id", USER).header("X-Correlation-Id", "c-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("c-1"));
    }

    @Test
    void missingUserHeaderIs401() throws Exception {
        mvc.perform(get("/wallets/me/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedUserHeaderIs401() throws Exception {
        mvc.perform(get("/wallets/me/balance").header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingDetails() throws Exception {
        when(getBalance.execute(USER)).thenThrow(new IllegalStateException("db password is hunter2"));

        mvc.perform(get("/wallets/me/balance").header("X-User-Id", USER))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error."));
    }
}

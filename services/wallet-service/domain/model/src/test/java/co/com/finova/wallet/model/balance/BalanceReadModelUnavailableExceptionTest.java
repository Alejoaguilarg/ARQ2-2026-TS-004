package co.com.finova.wallet.model.balance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceReadModelUnavailableExceptionTest {

    @Test
    void keepsMessageAndCause() {
        IllegalStateException cause = new IllegalStateException("connection refused");

        BalanceReadModelUnavailableException e = new BalanceReadModelUnavailableException("read failed", cause);

        assertThat(e).hasMessage("read failed").hasCause(cause);
    }
}

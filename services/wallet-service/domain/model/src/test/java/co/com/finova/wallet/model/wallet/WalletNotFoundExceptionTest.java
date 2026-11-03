package co.com.finova.wallet.model.wallet;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletNotFoundExceptionTest {

    @Test
    void carriesTheUserWithoutWallet() {
        UUID userId = UUID.randomUUID();

        WalletNotFoundException e = new WalletNotFoundException(userId);

        assertThat(e.userId()).isEqualTo(userId);
        assertThat(e).hasMessage("user " + userId + " has no wallet");
    }
}

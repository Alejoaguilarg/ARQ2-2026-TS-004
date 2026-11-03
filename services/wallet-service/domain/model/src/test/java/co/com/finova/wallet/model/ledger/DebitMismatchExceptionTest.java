package co.com.finova.wallet.model.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DebitMismatchExceptionTest {

    @Test
    void carriesTheTransferAndExplainsTheMismatch() {
        UUID transferId = UUID.randomUUID();

        DebitMismatchException e = new DebitMismatchException(transferId, "amount differs");

        assertThat(e.transferId()).isEqualTo(transferId);
        assertThat(e).hasMessage("no matching DEBIT for transfer " + transferId + ": amount differs");
    }
}

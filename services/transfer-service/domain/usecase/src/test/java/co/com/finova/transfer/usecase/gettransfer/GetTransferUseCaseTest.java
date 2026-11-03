package co.com.finova.transfer.usecase.gettransfer;

import co.com.finova.transfer.model.common.Currency;
import co.com.finova.transfer.model.money.Money;
import co.com.finova.transfer.model.transfer.Transfer;
import co.com.finova.transfer.model.transfer.TransferNotFoundException;
import co.com.finova.transfer.model.transfer.TransferRequest;
import co.com.finova.transfer.usecase.support.InMemoryPorts;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §3.9: visible to the sender and the recipient only. */
class GetTransferUseCaseTest {

    private final InMemoryPorts ports = new InMemoryPorts();
    private final GetTransferUseCase useCase = new GetTransferUseCase(ports.transferRepository);
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final Transfer transfer = Transfer.create(new TransferRequest(sender, recipient, Money.parse("10.00"),
            Currency.COP, null, UUID.randomUUID(), "f".repeat(64)), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), InMemoryPorts.NOW);

    @Test
    void theSenderAndTheRecipientSeeTheTransfer() {
        ports.transfers.put(transfer.transferId(), transfer);

        assertThat(useCase.handle(transfer.transferId(), sender)).isEqualTo(transfer);
        assertThat(useCase.handle(transfer.transferId(), recipient)).isEqualTo(transfer);
    }

    @Test
    void anyoneElseGetsNotFoundAsForAMissingTransfer() {
        ports.transfers.put(transfer.transferId(), transfer);

        assertThatThrownBy(() -> useCase.handle(transfer.transferId(), UUID.randomUUID()))
                .isInstanceOf(TransferNotFoundException.class);
        assertThatThrownBy(() -> useCase.handle(UUID.randomUUID(), sender))
                .isInstanceOf(TransferNotFoundException.class);
    }
}

package co.com.finova.wallet.model.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateLedgerEntryExceptionTest {

    @Test
    void carriesTransferAndTypeOfTheRejectedEntry() {
        UUID transferId = UUID.randomUUID();

        DuplicateLedgerEntryException e = new DuplicateLedgerEntryException(transferId, LedgerEntryType.DEBIT);

        assertThat(e.transferId()).isEqualTo(transferId);
        assertThat(e.type()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(e).hasMessage("ledger already has a DEBIT entry for transfer " + transferId);
    }
}

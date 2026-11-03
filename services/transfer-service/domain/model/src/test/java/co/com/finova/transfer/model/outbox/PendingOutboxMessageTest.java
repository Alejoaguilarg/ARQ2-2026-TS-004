package co.com.finova.transfer.model.outbox;

import co.com.finova.transfer.model.common.ContractViolationException;
import co.com.finova.transfer.model.message.OutboundMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingOutboxMessageTest {

    private static final UUID ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID CORRELATION_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID TRANSFER_ID = UUID.fromString("9b2f6c1e-3d4a-4f5b-8c7d-1e2f3a4b5c6d");
    private static final String JSON = "{\"messageId\":\"7c9e6679-7425-40de-944b-e07fc1f90ae7\"}";

    @Test
    void becomesTheOutboundMessageWithTheStoredJsonUnchanged() {
        PendingOutboxMessage pending = new PendingOutboxMessage(ID, CORRELATION_ID, TRANSFER_ID,
                "wallet.events", "wallet.debited", JSON);

        assertThat(pending.toOutbound()).isEqualTo(new OutboundMessage(ID.toString(), CORRELATION_ID.toString(),
                "wallet.events", "wallet.debited", JSON));
    }

    @Test
    void allowsMessagesThatBelongToNoTransfer() {
        PendingOutboxMessage pending = new PendingOutboxMessage(ID, CORRELATION_ID, null,
                "wallet.events", "wallet.balance-changed", JSON);

        assertThat(pending.transferId()).isNull();
    }

    @Test
    void requiresEveryOtherField() {
        assertInvalid(() -> new PendingOutboxMessage(null, CORRELATION_ID, TRANSFER_ID, "wallet.events", "k", JSON));
        assertInvalid(() -> new PendingOutboxMessage(ID, null, TRANSFER_ID, "wallet.events", "k", JSON));
        assertInvalid(() -> new PendingOutboxMessage(ID, CORRELATION_ID, TRANSFER_ID, " ", "k", JSON));
        assertInvalid(() -> new PendingOutboxMessage(ID, CORRELATION_ID, TRANSFER_ID, "wallet.events", null, JSON));
        assertInvalid(() -> new PendingOutboxMessage(ID, CORRELATION_ID, TRANSFER_ID, "wallet.events", "k", ""));
    }

    private static void assertInvalid(Runnable construction) {
        assertThatThrownBy(construction::run).isInstanceOf(ContractViolationException.class);
    }
}

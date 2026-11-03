package co.com.finova.notification.model.notification;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TransferOutcomeTest {

    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();

    @Test
    void completedTransferNotifiesSenderAndRecipient() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_COMPLETED, null);

        assertThat(outcome.usersToNotify()).containsExactly(SENDER, RECIPIENT);
    }

    @Test
    void rejectedTransferOnlyNotifiesSender() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_REJECTED, "INSUFFICIENT_FUNDS");

        assertThat(outcome.usersToNotify()).containsExactly(SENDER);
    }

    @Test
    void reversedTransferOnlyNotifiesSender() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_REVERSED, "CREDIT_TIMEOUT");

        assertThat(outcome.usersToNotify()).containsExactly(SENDER);
    }

    @Test
    void requiresTheUsersOfTheTransfer() {
        assertThatNullPointerException().isThrownBy(() -> new TransferOutcome(
                TransferEventType.TRANSFER_COMPLETED, TRANSFER, SENDER, null, "1.00", "COP", null, null));
    }

    @Test
    void messageTypeMatchesTheSpecNames() {
        assertThat(TransferEventType.fromMessageType("TransferCompleted"))
                .contains(TransferEventType.TRANSFER_COMPLETED);
        assertThat(TransferEventType.fromMessageType("TransferRejected"))
                .contains(TransferEventType.TRANSFER_REJECTED);
        assertThat(TransferEventType.fromMessageType("TransferReversed"))
                .contains(TransferEventType.TRANSFER_REVERSED);
        assertThat(TransferEventType.fromMessageType("WalletDebited")).isEmpty();
    }

    private static TransferOutcome outcome(TransferEventType type, String reason) {
        return new TransferOutcome(type, TRANSFER, SENDER, RECIPIENT, "150000.00", "COP", reason, "corr-1");
    }
}

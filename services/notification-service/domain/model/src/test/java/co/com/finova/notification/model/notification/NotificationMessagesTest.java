package co.com.finova.notification.model.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessagesTest {

    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();

    @Test
    void completedTransferHasDifferentTextForSenderAndRecipient() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_COMPLETED, null);

        assertThat(NotificationMessages.body(outcome, SENDER)).startsWith("You sent 150000.00 COP");
        assertThat(NotificationMessages.body(outcome, RECIPIENT)).startsWith("You received 150000.00 COP");
        assertThat(NotificationMessages.subject(outcome, SENDER)).isEqualTo("Finova: transfer sent");
        assertThat(NotificationMessages.subject(outcome, RECIPIENT)).isEqualTo("Finova: you received a transfer");
    }

    @Test
    void rejectedTransferExplainsTheReason() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_REJECTED, "INSUFFICIENT_FUNDS");

        assertThat(NotificationMessages.subject(outcome, SENDER)).isEqualTo("Finova: transfer rejected");
        assertThat(NotificationMessages.body(outcome, SENDER))
                .contains("was rejected: insufficient funds")
                .contains("No money was taken")
                .contains(TRANSFER.toString());
    }

    @Test
    void reversedTransferSaysTheMoneyWasReturned() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_REVERSED, "CREDIT_TIMEOUT");

        assertThat(NotificationMessages.subject(outcome, SENDER)).isEqualTo("Finova: transfer reversed");
        assertThat(NotificationMessages.body(outcome, SENDER)).contains("The money was returned");
    }

    @ParameterizedTest
    @CsvSource({
            "INSUFFICIENT_FUNDS, insufficient funds",
            "SENDER_WALLET_NOT_FOUND, your wallet was not found",
            "RECIPIENT_WALLET_NOT_FOUND, the recipient has no Finova wallet",
            "CREDIT_TIMEOUT, the recipient could not receive the money in time",
            "SOMETHING_NEW, unspecified reason"
    })
    void describesEveryReasonOfTheSpec(String reason, String text) {
        assertThat(NotificationMessages.describe(reason)).isEqualTo(text);
    }

    @Test
    void missingReasonHasAGenericText() {
        assertThat(NotificationMessages.describe(null)).isEqualTo("unspecified reason");
    }

    private static TransferOutcome outcome(TransferEventType type, String reason) {
        return new TransferOutcome(type, TRANSFER, SENDER, RECIPIENT, "150000.00", "COP", reason, null);
    }
}

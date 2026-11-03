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

        assertThat(NotificationMessages.body(outcome, SENDER)).startsWith("Enviaste 150000.00 COP");
        assertThat(NotificationMessages.body(outcome, RECIPIENT)).startsWith("Recibiste 150000.00 COP");
        assertThat(NotificationMessages.subject(outcome, SENDER)).isEqualTo("Finova: transferencia enviada");
        assertThat(NotificationMessages.subject(outcome, RECIPIENT)).isEqualTo("Finova: recibiste una transferencia");
    }

    @Test
    void rejectedTransferExplainsTheReason() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_REJECTED, "INSUFFICIENT_FUNDS");

        assertThat(NotificationMessages.subject(outcome, SENDER)).isEqualTo("Finova: transferencia rechazada");
        assertThat(NotificationMessages.body(outcome, SENDER))
                .contains("fue rechazada: fondos insuficientes")
                .contains("No se descontó dinero")
                .contains(TRANSFER.toString());
    }

    @Test
    void reversedTransferSaysTheMoneyWasReturned() {
        TransferOutcome outcome = outcome(TransferEventType.TRANSFER_REVERSED, "CREDIT_TIMEOUT");

        assertThat(NotificationMessages.subject(outcome, SENDER)).isEqualTo("Finova: transferencia reversada");
        assertThat(NotificationMessages.body(outcome, SENDER)).contains("El dinero fue devuelto");
    }

    @ParameterizedTest
    @CsvSource({
            "INSUFFICIENT_FUNDS, fondos insuficientes",
            "SENDER_WALLET_NOT_FOUND, no se encontró tu billetera",
            "RECIPIENT_WALLET_NOT_FOUND, no se encontró la billetera del destinatario",
            "CREDIT_TIMEOUT, el destinatario no pudo recibir el dinero a tiempo",
            "SOMETHING_NEW, motivo no especificado"
    })
    void describesEveryReasonOfTheSpec(String reason, String text) {
        assertThat(NotificationMessages.describe(reason)).isEqualTo(text);
    }

    @Test
    void missingReasonHasAGenericText() {
        assertThat(NotificationMessages.describe(null)).isEqualTo("motivo no especificado");
    }

    private static TransferOutcome outcome(TransferEventType type, String reason) {
        return new TransferOutcome(type, TRANSFER, SENDER, RECIPIENT, "150000.00", "COP", reason, null);
    }
}

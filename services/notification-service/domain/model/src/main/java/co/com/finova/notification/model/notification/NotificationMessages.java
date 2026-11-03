package co.com.finova.notification.model.notification;

import java.util.UUID;

/**
 * Text that each user receives for a transfer outcome. The wording is a business decision, so it
 * lives in the domain and not in a provider adapter.
 */
public final class NotificationMessages {

    private NotificationMessages() {
    }

    public static String subject(TransferOutcome outcome, UUID userId) {
        return switch (outcome.type()) {
            case TRANSFER_COMPLETED -> outcome.isSender(userId)
                    ? "Finova: transferencia enviada"
                    : "Finova: recibiste una transferencia";
            case TRANSFER_REJECTED -> "Finova: transferencia rechazada";
            case TRANSFER_REVERSED -> "Finova: transferencia reversada";
        };
    }

    public static String body(TransferOutcome outcome, UUID userId) {
        String money = outcome.amount() + " " + outcome.currency();
        String text = switch (outcome.type()) {
            case TRANSFER_COMPLETED -> outcome.isSender(userId)
                    ? "Enviaste " + money + ". La transferencia se completó con éxito."
                    : "Recibiste " + money + " en tu billetera Finova.";
            case TRANSFER_REJECTED -> "Tu transferencia de " + money + " fue rechazada: "
                    + describe(outcome.reason()) + ". No se descontó dinero de tu billetera.";
            case TRANSFER_REVERSED -> "Tu transferencia de " + money + " no se pudo completar ("
                    + describe(outcome.reason()) + "). El dinero fue devuelto a tu billetera.";
        };
        return text + "\nReferencia: " + outcome.transferId();
    }

    /** Reasons defined in spec §4.4 (TransferRejected and TransferReversed). */
    static String describe(String reason) {
        if (reason == null) {
            return "motivo no especificado";
        }
        return switch (reason) {
            case "INSUFFICIENT_FUNDS" -> "fondos insuficientes";
            case "SENDER_WALLET_NOT_FOUND" -> "no se encontró tu billetera";
            case "RECIPIENT_WALLET_NOT_FOUND" -> "no se encontró la billetera del destinatario";
            case "CREDIT_TIMEOUT" -> "el destinatario no pudo recibir el dinero a tiempo";
            default -> "motivo no especificado";
        };
    }
}

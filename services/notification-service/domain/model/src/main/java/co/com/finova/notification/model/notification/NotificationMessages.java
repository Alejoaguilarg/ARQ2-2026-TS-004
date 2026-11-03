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
                    ? "Finova: transfer sent"
                    : "Finova: you received a transfer";
            case TRANSFER_REJECTED -> "Finova: transfer rejected";
            case TRANSFER_REVERSED -> "Finova: transfer reversed";
        };
    }

    public static String body(TransferOutcome outcome, UUID userId) {
        String money = outcome.amount() + " " + outcome.currency();
        String text = switch (outcome.type()) {
            case TRANSFER_COMPLETED -> outcome.isSender(userId)
                    ? "You sent " + money + ". The transfer was completed."
                    : "You received " + money + " in your Finova wallet.";
            case TRANSFER_REJECTED -> "Your transfer of " + money + " was rejected: "
                    + describe(outcome.reason()) + ". No money was taken from your wallet.";
            case TRANSFER_REVERSED -> "Your transfer of " + money + " could not be completed ("
                    + describe(outcome.reason()) + "). The money was returned to your wallet.";
        };
        return text + "\nReference: " + outcome.transferId();
    }

    /** Reasons defined in spec §4.4 (TransferRejected and TransferReversed). */
    static String describe(String reason) {
        if (reason == null) {
            return "unspecified reason";
        }
        return switch (reason) {
            case "INSUFFICIENT_FUNDS" -> "insufficient funds";
            case "SENDER_WALLET_NOT_FOUND" -> "your wallet was not found";
            case "RECIPIENT_WALLET_NOT_FOUND" -> "the recipient has no Finova wallet";
            case "CREDIT_TIMEOUT" -> "the recipient could not receive the money in time";
            default -> "unspecified reason";
        };
    }
}

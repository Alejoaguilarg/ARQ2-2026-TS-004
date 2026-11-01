package co.com.finova.transfer.model.message.event;

import java.util.Objects;

public class TransferRejected {
    public enum Reason {
        INSUFFICIENT_FUNDS,
        SENDER_WALLET_NOT_FOUND
    }

    private String transferId;
    private String senderUserId;
    private String recipientUserId;
    private String amount;
    private String currency;
    private Reason reason;

    public TransferRejected() {
    }

    public TransferRejected(String transferId, String senderUserId,
                            String recipientUserId, String amount, String currency, Reason reason) {
        this.transferId = transferId;
        this.senderUserId = senderUserId;
        this.recipientUserId = recipientUserId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getSenderUserId() { return senderUserId; }
    public void setSenderUserId(String senderUserId) { this.senderUserId = senderUserId; }

    public String getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(String recipientUserId) { this.recipientUserId = recipientUserId; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Reason getReason() { return reason; }
    public void setReason(Reason reason) { this.reason = reason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransferRejected)) return false;
        TransferRejected that = (TransferRejected) o;
        return Objects.equals(transferId, that.transferId)
            && Objects.equals(senderUserId, that.senderUserId)
            && Objects.equals(recipientUserId, that.recipientUserId)
            && Objects.equals(amount, that.amount)
            && Objects.equals(currency, that.currency)
            && reason == that.reason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, senderUserId, recipientUserId, amount, currency, reason);
    }

    @Override
    public String toString() {
        return "TransferRejected{transferId='" + transferId + "', senderUserId='" + senderUserId
            + "', recipientUserId='" + recipientUserId + "', amount='" + amount + "', currency='" + currency + "', reason=" + reason + "}";
    }
}

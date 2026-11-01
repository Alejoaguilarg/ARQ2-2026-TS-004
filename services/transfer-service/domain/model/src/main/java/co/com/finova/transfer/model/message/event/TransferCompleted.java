package co.com.finova.transfer.model.message.event;

import java.time.Instant;
import java.util.Objects;

public class TransferCompleted {
    private String transferId;
    private String senderUserId;
    private String recipientUserId;
    private String amount;
    private String currency;
    private Instant completedAt;

    public TransferCompleted() {
    }

    public TransferCompleted(String transferId, String senderUserId,
                             String recipientUserId, String amount, String currency,
                             Instant completedAt) {
        this.transferId = transferId;
        this.senderUserId = senderUserId;
        this.recipientUserId = recipientUserId;
        this.amount = amount;
        this.currency = currency;
        this.completedAt = completedAt;
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

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransferCompleted)) return false;
        TransferCompleted that = (TransferCompleted) o;
        return Objects.equals(transferId, that.transferId)
            && Objects.equals(senderUserId, that.senderUserId)
            && Objects.equals(recipientUserId, that.recipientUserId)
            && Objects.equals(amount, that.amount)
            && Objects.equals(currency, that.currency)
            && Objects.equals(completedAt, that.completedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, senderUserId, recipientUserId, amount, currency, completedAt);
    }

    @Override
    public String toString() {
        return "TransferCompleted{transferId='" + transferId + "', senderUserId='" + senderUserId
            + "', recipientUserId='" + recipientUserId + "', amount='" + amount + "', currency='" + currency + "'}";
    }
}

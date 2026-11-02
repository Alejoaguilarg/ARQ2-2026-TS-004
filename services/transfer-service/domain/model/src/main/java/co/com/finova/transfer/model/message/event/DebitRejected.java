package co.com.finova.transfer.model.message.event;

import java.util.Objects;

public class DebitRejected {
    public enum Reason {
        INSUFFICIENT_FUNDS,
        SENDER_WALLET_NOT_FOUND
    }

    private String transferId;
    private String userId;
    private String amount;
    private Reason reason;

    public DebitRejected() {
    }

    public DebitRejected(String transferId, String userId, String amount, Reason reason) {
        this.transferId = transferId;
        this.userId = userId;
        this.amount = amount;
        this.reason = reason;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public Reason getReason() { return reason; }
    public void setReason(Reason reason) { this.reason = reason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DebitRejected)) return false;
        DebitRejected that = (DebitRejected) o;
        return Objects.equals(transferId, that.transferId)
            && Objects.equals(userId, that.userId)
            && Objects.equals(amount, that.amount)
            && reason == that.reason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, userId, amount, reason);
    }

    @Override
    public String toString() {
        return "DebitRejected{transferId='" + transferId + "', userId='" + userId
            + "', amount='" + amount + "', reason=" + reason + "}";
    }
}

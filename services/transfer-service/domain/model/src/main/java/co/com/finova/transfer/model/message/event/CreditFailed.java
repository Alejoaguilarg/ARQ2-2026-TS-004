package co.com.finova.transfer.model.message.event;

import java.util.Objects;

public class CreditFailed {
    public enum Reason {
        RECIPIENT_WALLET_NOT_FOUND,
        TRANSFER_REVERSED
    }

    private String transferId;
    private String userId;
    private int attempt;
    private Reason reason;

    public CreditFailed() {
    }

    public CreditFailed(String transferId, String userId, int attempt, Reason reason) {
        this.transferId = transferId;
        this.userId = userId;
        this.attempt = attempt;
        this.reason = reason;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public Reason getReason() { return reason; }
    public void setReason(Reason reason) { this.reason = reason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CreditFailed)) return false;
        CreditFailed that = (CreditFailed) o;
        return attempt == that.attempt
            && Objects.equals(transferId, that.transferId)
            && Objects.equals(userId, that.userId)
            && reason == that.reason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, userId, attempt, reason);
    }

    @Override
    public String toString() {
        return "CreditFailed{transferId='" + transferId + "', userId='" + userId
            + "', attempt=" + attempt + ", reason=" + reason + "}";
    }
}

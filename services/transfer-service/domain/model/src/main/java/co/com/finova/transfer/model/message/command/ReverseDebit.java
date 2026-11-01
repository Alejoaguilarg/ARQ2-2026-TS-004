package co.com.finova.transfer.model.message.command;

import java.util.Objects;

public class ReverseDebit {
    public enum Reason {
        RECIPIENT_WALLET_NOT_FOUND,
        CREDIT_TIMEOUT
    }

    private String transferId;
    private String userId;
    private String amount;
    private String currency;
    private Reason reason;

    public ReverseDebit() {
    }

    public ReverseDebit(String transferId, String userId, String amount,
                        String currency, Reason reason) {
        this.transferId = transferId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Reason getReason() { return reason; }
    public void setReason(Reason reason) { this.reason = reason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReverseDebit)) return false;
        ReverseDebit that = (ReverseDebit) o;
        return Objects.equals(transferId, that.transferId)
            && Objects.equals(userId, that.userId)
            && Objects.equals(amount, that.amount)
            && Objects.equals(currency, that.currency)
            && reason == that.reason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, userId, amount, currency, reason);
    }

    @Override
    public String toString() {
        return "ReverseDebit{transferId='" + transferId + "', userId='" + userId
            + "', amount='" + amount + "', currency='" + currency + "', reason=" + reason + "}";
    }
}

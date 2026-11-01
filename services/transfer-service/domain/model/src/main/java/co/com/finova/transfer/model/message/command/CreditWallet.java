package co.com.finova.transfer.model.message.command;

import java.util.Objects;

public class CreditWallet {
    private String transferId;
    private String userId;
    private String amount;
    private String currency;
    private int attempt;

    public CreditWallet() {
    }

    public CreditWallet(String transferId, String userId, String amount,
                        String currency, int attempt) {
        this.transferId = transferId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.attempt = attempt;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CreditWallet)) return false;
        CreditWallet that = (CreditWallet) o;
        return attempt == that.attempt
            && Objects.equals(transferId, that.transferId)
            && Objects.equals(userId, that.userId)
            && Objects.equals(amount, that.amount)
            && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, userId, amount, currency, attempt);
    }

    @Override
    public String toString() {
        return "CreditWallet{transferId='" + transferId + "', userId='" + userId
            + "', amount='" + amount + "', currency='" + currency + "', attempt=" + attempt + "}";
    }
}

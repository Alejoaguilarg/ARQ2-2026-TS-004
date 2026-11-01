package co.com.finova.transfer.model.message.command;

import java.util.Objects;

public class DebitWallet {
    private String transferId;
    private String userId;
    private String amount;
    private String currency;

    public DebitWallet() {
    }

    public DebitWallet(String transferId, String userId, String amount, String currency) {
        this.transferId = transferId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DebitWallet)) return false;
        DebitWallet that = (DebitWallet) o;
        return Objects.equals(transferId, that.transferId)
            && Objects.equals(userId, that.userId)
            && Objects.equals(amount, that.amount)
            && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, userId, amount, currency);
    }

    @Override
    public String toString() {
        return "DebitWallet{transferId='" + transferId + "', userId='" + userId
            + "', amount='" + amount + "', currency='" + currency + "'}";
    }
}

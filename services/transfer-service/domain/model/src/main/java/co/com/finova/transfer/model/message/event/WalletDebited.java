package co.com.finova.transfer.model.message.event;

import java.util.Objects;

public class WalletDebited {
    private String transferId;
    private String walletId;
    private String userId;
    private String amount;
    private String currency;
    private int version;

    public WalletDebited() {
    }

    public WalletDebited(String transferId, String walletId, String userId,
                         String amount, String currency, int version) {
        this.transferId = transferId;
        this.walletId = walletId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.version = version;
    }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WalletDebited)) return false;
        WalletDebited that = (WalletDebited) o;
        return version == that.version
            && Objects.equals(transferId, that.transferId)
            && Objects.equals(walletId, that.walletId)
            && Objects.equals(userId, that.userId)
            && Objects.equals(amount, that.amount)
            && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, walletId, userId, amount, currency, version);
    }

    @Override
    public String toString() {
        return "WalletDebited{transferId='" + transferId + "', walletId='" + walletId
            + "', userId='" + userId + "', amount='" + amount + "', currency='" + currency + "', version=" + version + "}";
    }
}

package co.com.finova.transfer.model.message.event;

import java.util.Objects;

public class DebitReversed {
    private String transferId;
    private String walletId;
    private String userId;
    private String amount;
    private int version;

    public DebitReversed() {
    }

    public DebitReversed(String transferId, String walletId, String userId,
                         String amount, int version) {
        this.transferId = transferId;
        this.walletId = walletId;
        this.userId = userId;
        this.amount = amount;
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

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DebitReversed)) return false;
        DebitReversed that = (DebitReversed) o;
        return version == that.version
            && Objects.equals(transferId, that.transferId)
            && Objects.equals(walletId, that.walletId)
            && Objects.equals(userId, that.userId)
            && Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, walletId, userId, amount, version);
    }

    @Override
    public String toString() {
        return "DebitReversed{transferId='" + transferId + "', walletId='" + walletId
            + "', userId='" + userId + "', amount='" + amount + "', version=" + version + "}";
    }
}

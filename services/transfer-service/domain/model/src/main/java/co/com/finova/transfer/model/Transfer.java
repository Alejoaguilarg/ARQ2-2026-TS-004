package co.com.finova.transfer.model;

import java.time.Instant;

public class Transfer {
    private String transferId;
    private String senderUserId;
    private String recipientUserId;
    private String amount;
    private String currency;
    private String idempotencyKey;
    private String requestHash;
    private TransferStatus status;
    private String failureReason;
    private int creditAttempts;
    private String correlationId;
    private Instant lastCommandAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public Transfer() {
    }

    public Transfer(String transferId, String senderUserId, String recipientUserId,
                    String amount, String currency, String idempotencyKey,
                    String requestHash, String correlationId) {
        this.transferId = transferId;
        this.senderUserId = senderUserId;
        this.recipientUserId = recipientUserId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = TransferStatus.PENDING;
        this.failureReason = null;
        this.creditAttempts = 0;
        this.correlationId = correlationId;
        Instant now = Instant.now();
        this.lastCommandAt = now;
        this.createdAt = now;
        this.updatedAt = now;
        this.completedAt = null;
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

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }

    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public int getCreditAttempts() { return creditAttempts; }
    public void setCreditAttempts(int creditAttempts) { this.creditAttempts = creditAttempts; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public Instant getLastCommandAt() { return lastCommandAt; }
    public void setLastCommandAt(Instant lastCommandAt) { this.lastCommandAt = lastCommandAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public boolean isTerminal() {
        return status == TransferStatus.COMPLETED
            || status == TransferStatus.REJECTED
            || status == TransferStatus.REVERSED;
    }
}

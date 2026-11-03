package co.com.finova.transfer.jpa.transfer;

import co.com.finova.transfer.model.transfer.TransferStatus;
import java.time.Instant;
import java.util.Objects;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "transfers")
public class TransferJpaEntity {

    @Id
    @Column(name = "transfer_id")
    private String transferId;

    @Column(name = "sender_user_id", nullable = false)
    private String senderUserId;

    @Column(name = "recipient_user_id", nullable = false)
    private String recipientUserId;

    @Column(name = "amount", nullable = false)
    private String amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "credit_attempts", nullable = false)
    private int creditAttempts;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "last_command_at")
    private Instant lastCommandAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public TransferJpaEntity() {
    }

    public TransferJpaEntity(String transferId, String senderUserId, String recipientUserId,
                             String amount, String currency, String idempotencyKey,
                             String requestHash, String correlationId) {
        this.transferId = transferId;
        this.senderUserId = senderUserId;
        this.recipientUserId = recipientUserId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.correlationId = correlationId;
        this.status = TransferStatus.PENDING.name();
        this.failureReason = null;
        this.creditAttempts = 0;
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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransferJpaEntity)) return false;
        TransferJpaEntity that = (TransferJpaEntity) o;
        return creditAttempts == that.creditAttempts && Objects.equals(transferId, that.transferId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId);
    }
}

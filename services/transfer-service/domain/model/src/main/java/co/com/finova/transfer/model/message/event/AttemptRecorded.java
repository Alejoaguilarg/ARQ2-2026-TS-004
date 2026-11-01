package co.com.finova.transfer.model.message.event;

import java.time.Instant;
import java.util.Objects;

public class AttemptRecorded {
    public enum Source {
        GATEWAY,
        TRANSFER,
        WALLET
    }

    public enum Stage {
        REQUEST,
        DEBIT,
        CREDIT,
        REVERSAL,
        TRANSFER
    }

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        DUPLICATE,
        FAILED,
        COMPENSATED,
        COMPLETED,
        APPLIED
    }

    private String eventId;
    private Source source;
    private Stage stage;
    private Outcome outcome;
    private String reason;
    private String transferId;
    private String userId;
    private String idempotencyKey;
    private String amount;
    private Integer httpStatus;
    private String correlationId;
    private Instant occurredAt;

    public AttemptRecorded() {
    }

    public AttemptRecorded(String eventId, Source source, Stage stage, Outcome outcome,
                           String reason, String transferId, String userId,
                           String idempotencyKey, String amount, Integer httpStatus,
                           String correlationId, Instant occurredAt) {
        this.eventId = eventId;
        this.source = source;
        this.stage = stage;
        this.outcome = outcome;
        this.reason = reason;
        this.transferId = transferId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.httpStatus = httpStatus;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public Stage getStage() { return stage; }
    public void setStage(Stage stage) { this.stage = stage; }

    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttemptRecorded)) return false;
        AttemptRecorded that = (AttemptRecorded) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "AttemptRecorded{eventId='" + eventId + "', source=" + source
            + ", stage=" + stage + ", outcome=" + outcome + "}";
    }
}

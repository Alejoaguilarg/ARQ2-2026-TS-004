package co.com.finova.transfer.jpa;

import java.time.Instant;
import java.util.Objects;

public class ProcessedMessage {
    private String messageId;
    private String consumer;
    private Instant processedAt;
    private String resultType;
    private String resultPayload;

    public ProcessedMessage() {
    }

    public ProcessedMessage(String messageId, String consumer, Instant processedAt,
                            String resultType, String resultPayload) {
        this.messageId = messageId;
        this.consumer = consumer;
        this.processedAt = processedAt;
        this.resultType = resultType;
        this.resultPayload = resultPayload;
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getConsumer() { return consumer; }
    public void setConsumer(String consumer) { this.consumer = consumer; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    public String getResultType() { return resultType; }
    public void setResultType(String resultType) { this.resultType = resultType; }

    public String getResultPayload() { return resultPayload; }
    public void setResultPayload(String resultPayload) { this.resultPayload = resultPayload; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedMessage)) return false;
        ProcessedMessage that = (ProcessedMessage) o;
        return Objects.equals(messageId, that.messageId) && Objects.equals(consumer, that.consumer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, consumer);
    }

    @Override
    public String toString() {
        return "ProcessedMessage{messageId='" + messageId + "', consumer='" + consumer + "'}";
    }
}

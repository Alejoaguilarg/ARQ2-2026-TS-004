package co.com.finova.wallet.jpa.processedmessage;

import co.com.finova.wallet.messagejson.MessageJsonMapper;
import co.com.finova.wallet.model.message.MessageType;
import co.com.finova.wallet.model.message.payload.MessagePayload;
import co.com.finova.wallet.model.processedmessage.ProcessedMessage;
import co.com.finova.wallet.model.processedmessage.gateways.ProcessedMessageRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProcessedMessageRepository implements ProcessedMessageRepository {

    private final JdbcClient jdbc;
    private final MessageJsonMapper json;

    public JdbcProcessedMessageRepository(JdbcClient jdbc, MessageJsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ProcessedMessage> find(UUID messageId, String consumer) {
        return jdbc.sql("""
                        SELECT message_id, consumer, processed_at, result_type, result_payload::text AS result_payload
                        FROM processed_messages
                        WHERE message_id = :messageId AND consumer = :consumer
                        """)
                .param("messageId", messageId)
                .param("consumer", consumer)
                .query(this::toProcessedMessage)
                .optional();
    }

    @Override
    public void save(ProcessedMessage message) {
        boolean hasResult = message.resultType() != null;
        jdbc.sql("""
                        INSERT INTO processed_messages
                            (message_id, consumer, processed_at, result_type, result_payload)
                        VALUES (:messageId, :consumer, :processedAt, :resultType, CAST(:resultPayload AS jsonb))
                        """)
                .param("messageId", message.messageId())
                .param("consumer", message.consumer())
                .param("processedAt", OffsetDateTime.ofInstant(message.processedAt(), ZoneOffset.UTC))
                .param("resultType", hasResult ? message.resultType().contractName() : null)
                .param("resultPayload", hasResult ? json.write(message.resultPayload()) : null)
                .update();
    }

    @Override
    public Optional<MessagePayload> findStoredResult(UUID transferId, MessageType resultType) {
        return jdbc.sql("""
                        SELECT result_payload::text
                        FROM processed_messages
                        WHERE result_type = :resultType AND result_payload ->> 'transferId' = :transferId
                        ORDER BY processed_at
                        LIMIT 1
                        """)
                .param("resultType", resultType.contractName())
                .param("transferId", transferId.toString())
                .query(String.class)
                .optional()
                .map(stored -> json.readResult(resultType, stored));
    }

    private ProcessedMessage toProcessedMessage(ResultSet rs, int rowNum) throws SQLException {
        String resultTypeName = rs.getString("result_type");
        MessageType resultType = resultTypeName == null ? null : MessageType.fromContractName(resultTypeName);
        MessagePayload result = resultType == null ? null : json.readResult(resultType, rs.getString("result_payload"));
        return new ProcessedMessage(
                rs.getObject("message_id", UUID.class),
                rs.getString("consumer"),
                rs.getObject("processed_at", OffsetDateTime.class).toInstant(),
                resultType,
                result);
    }
}

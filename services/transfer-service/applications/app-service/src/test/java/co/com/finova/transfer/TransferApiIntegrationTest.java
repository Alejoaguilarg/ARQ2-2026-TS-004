package co.com.finova.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service as the Gateway sees it, on a real PostgreSQL: POST /transfers stores the transfer and its outbox rows
 * in one transaction (spec §3.8, §4.6 rule 1), a repeated key is answered from transfer_db (§3.1.1) and
 * GET /transfers/{id} is visible to its two users only (§3.9).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.health.rabbit.enabled=false", "adapters.outbox.relay-enabled=false",
                "spring.rabbitmq.listener.simple.auto-startup=false", "adapters.saga.sweeper-enabled=false"})
@Testcontainers
class TransferApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transfer_db");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE outbox_messages, processed_messages, transfers");
    }

    private ResponseEntity<String> post(UUID key, String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", sender.toString());
        headers.add("X-Correlation-Id", correlationId.toString());
        headers.add("Idempotency-Key", key.toString());
        String body = """
                {"recipientUserId":"%s","amount":"%s","currency":"COP","description":"Dinner"}"""
                .formatted(recipient, amount);
        return http.exchange("/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String transferId, UUID caller) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", caller.toString());
        return http.exchange("/transfers/" + transferId, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void aTransferIsStoredWithItsDebitAndItsAuditInTheOutbox() throws Exception {
        ResponseEntity<String> response = post(UUID.randomUUID(), "150000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String transferId = JSON.readTree(response.getBody()).get("transferId").asText();
        assertThat(response.getHeaders().getLocation()).hasToString("/transfers/" + transferId);
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE transfer_id = ?::uuid", String.class,
                transferId)).isEqualTo("PENDING");

        List<JsonNode> outbox = jdbc.queryForList("SELECT payload::text FROM outbox_messages ORDER BY created_at",
                String.class).stream().map(TransferApiIntegrationTest::read).toList();
        assertThat(outbox).extracting(m -> m.get("type").asText())
                .containsExactlyInAnyOrder("DebitWallet", "AttemptRecorded");
        JsonNode debit = outbox.stream().filter(m -> m.get("type").asText().equals("DebitWallet")).findFirst()
                .orElseThrow();
        assertThat(debit.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(debit.get("transferId").asText()).isEqualTo(transferId);
        assertThat(debit.get("payload").get("userId").asText()).isEqualTo(sender.toString());
        assertThat(debit.get("payload").get("amount").asText()).isEqualTo("150000.00");
        assertThat(jdbc.queryForObject("SELECT last_command_id::text FROM transfers", String.class))
                .isEqualTo(debit.get("messageId").asText());
    }

    @Test
    void aRepeatedKeyIsAnsweredWithTheSameTransfer_F3_7() throws Exception {
        UUID key = UUID.randomUUID();
        String first = JSON.readTree(post(key, "150000.00").getBody()).get("transferId").asText();

        ResponseEntity<String> again = post(key, "150000.00");
        ResponseEntity<String> otherBody = post(key, "1.00");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(JSON.readTree(again.getBody()).get("transferId").asText()).isEqualTo(first);
        assertThat(otherBody.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(JSON.readTree(otherBody.getBody()).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE payload->>'type' = 'DebitWallet'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void onlyTheSenderAndTheRecipientSeeTheTransfer_s3_9() throws Exception {
        String transferId = JSON.readTree(post(UUID.randomUUID(), "10.00").getBody()).get("transferId").asText();

        ResponseEntity<String> asRecipient = get(transferId, recipient);
        ResponseEntity<String> asStranger = get(transferId, UUID.randomUUID());

        assertThat(asRecipient.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(asRecipient.getBody()).get("status").asText()).isEqualTo("PENDING");
        assertThat(JSON.readTree(asRecipient.getBody()).get("amount").asText()).isEqualTo("10.00");
        assertThat(get(transferId, sender).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asStranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JSON.readTree(asStranger.getBody()).get("code").asText()).isEqualTo("TRANSFER_NOT_FOUND");
    }

    @Test
    void anInvalidRequestCreatesNothingButIsAudited() throws Exception {
        ResponseEntity<String> response = post(UUID.randomUUID(), "0.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JSON.readTree(response.getBody()).get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT payload->'payload'->>'reason' FROM outbox_messages", String.class))
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void theServiceIsHealthy() {
        assertThat(http.getForObject("/actuator/health", String.class)).contains("\"status\":\"UP\"");
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

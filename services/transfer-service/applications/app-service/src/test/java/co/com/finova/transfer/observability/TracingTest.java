package co.com.finova.transfer.observability;

import co.com.finova.transfer.messaging.MessagingTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traces in Transfer (observability only; the spec does not define them): the commands Transfer publishes carry the
 * W3C trace context in their AMQP headers (never in the §4.1 envelope), the listener of transfer.wallet-events.q
 * continues the trace of an incoming result so its log lines carry [traceId,spanId], its span is tagged with
 * transfer.id and message.type (high cardinality only), and @Scheduled runs (relay, sweeper) open no observation.
 * The OTLP endpoint is a host that does not resolve, like Alloy when it is not running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.tracing.sampling.probability=1.0",
        "management.otlp.tracing.endpoint=http://otlp-collector.invalid:4318/v1/traces"})
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
@Import(TracingTest.Recording.class)
class TracingTest extends MessagingTestBase {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String TRACEPARENT = "^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private RecordedObservations recorded;

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @Test
    void theListenerContinuesTheIncomingTraceAndTagsItsSpan(CapturedOutput output) throws Exception {
        UUID transferId = createTransfer();
        Message debit = receive("wallet.commands.q", WAIT);
        assertThat(debit).as("DebitWallet relayed").isNotNull();
        String traceId = randomHex(32);

        publishRaw("wallet.debited", walletDebited(transferId), "00-" + traceId + "-" + randomHex(16) + "-01");

        String transition = "WalletDebited: PENDING -> DEBITED";
        await("transition log line", WAIT, () -> output.getOut().contains(transition));
        assertThat(lineContaining(output, transition)).containsPattern("\\[" + traceId + ",[0-9a-f]{16}\\] ");
        await("listener observation", WAIT, () -> recorded.listener(transferId).isPresent());
        Observation.Context listener = recorded.listener(transferId).orElseThrow();
        assertThat(value(listener.getHighCardinalityKeyValue("message.type"))).isEqualTo("WalletDebited");
        assertThat(listener.getLowCardinalityKeyValue("transfer.id"))
                .as("transfer.id never reaches the spring.rabbit.listener metric").isNull();
    }

    @Test
    void theCommandsTransferPublishesCarryTheTraceContextInTheirHeaders() throws Exception {
        createTransfer();

        Message debit = receive("wallet.commands.q", WAIT);

        assertThat(debit).as("DebitWallet relayed").isNotNull();
        assertThat((String) debit.getMessageProperties().getHeader("traceparent")).matches(TRACEPARENT);
        // The trace context stays out of the §4.1 envelope.
        assertThat(new String(debit.getBody(), StandardCharsets.UTF_8)).doesNotContain("traceparent");
    }

    @Test
    void scheduledRunsOpenNoObservation() throws Exception {
        createTransfer();
        assertThat(receive("wallet.commands.q", WAIT)).as("the relay ran").isNotNull();
        // The sweeper runs every 2 s: give it time to run at least once.
        pause(Duration.ofSeconds(3));

        assertThat(recorded.named("tasks.scheduled.execution"))
                .as("relay and sweeper runs would bury the real traces (and Alloy drops them)").isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private UUID createTransfer() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-User-Id", sender.toString());
        headers.add("X-Correlation-Id", correlationId.toString());
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> response = http.exchange("/transfers", HttpMethod.POST, new HttpEntity<>("""
                {"recipientUserId":"%s","amount":"150000.00","currency":"COP"}""".formatted(recipient), headers),
                String.class);
        return UUID.fromString(JSON.readTree(response.getBody()).get("transferId").asText());
    }

    private String walletDebited(UUID transferId) {
        return """
                {"transferId":"%s","walletId":"%s","userId":"%s","amount":"150000.00","version":2}"""
                .formatted(transferId, UUID.randomUUID(), sender);
    }

    /** Publishes a Wallet result straight on the channel, so the test's RabbitTemplate cannot add trace headers. */
    private void publishRaw(String routingKey, String payloadJson, String traceparent) {
        String messageId = UUID.randomUUID().toString();
        UUID transferId = UUID.fromString(readTransferId(payloadJson));
        String json = """
                {"messageId":"%s","type":"WalletDebited","correlationId":"%s","transferId":"%s",
                 "producer":"wallet-service","occurredAt":"%s","payload":%s}"""
                .formatted(messageId, correlationId, transferId, Instant.now(), payloadJson);
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .messageId(messageId)
                .correlationId(correlationId.toString())
                .contentType("application/json")
                .headers(Map.of("traceparent", traceparent))
                .build();
        rabbit.execute(channel -> {
            channel.basicPublish("wallet.events", routingKey, properties, json.getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    private static String readTransferId(String payloadJson) {
        try {
            return JSON.readTree(payloadJson).get("transferId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int length) {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, length);
    }

    private static String lineContaining(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no log line contains: " + text));
    }

    private static String value(KeyValue keyValue) {
        return keyValue == null ? null : keyValue.getValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {
        @Bean
        RecordedObservations recordedObservations() {
            return new RecordedObservations();
        }
    }

    /** Every observation the service stops, as the tracing handler sees it (Spring Boot registers handler beans). */
    static class RecordedObservations implements ObservationHandler<Observation.Context> {

        private final Queue<Observation.Context> stopped = new ConcurrentLinkedQueue<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }

        Optional<Observation.Context> listener(UUID transferId) {
            return named("spring.rabbit.listener").stream()
                    .filter(context -> transferId.toString().equals(value(context.getHighCardinalityKeyValue(
                            "transfer.id"))))
                    .findFirst();
        }

        java.util.List<Observation.Context> named(String name) {
            return stopped.stream().filter(context -> name.equals(context.getName())).toList();
        }
    }
}

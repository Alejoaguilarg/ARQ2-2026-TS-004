package co.com.finova.wallet.observability;

import co.com.finova.wallet.messaging.MessagingTestBase;
import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traces (Etapa 7, priority 3, part A): spans go to an OTLP endpoint only through environment variables, the W3C
 * trace context travels in the AMQP headers (never in the §4.1 envelope), log lines end with [traceId,spanId] after
 * [correlationId] [transferId], and an unreachable collector never slows the service down.
 * The endpoint is a host that does not resolve, like Alloy when it is not running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.tracing.sampling.probability=1.0",
        "management.otlp.tracing.endpoint=http://otlp-collector.invalid:4318/v1/traces"})
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class TracingTest extends MessagingTestBase {

    private static final String TRACEPARENT = "^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$";

    @Autowired
    private TestRestTemplate http;

    @Test
    void messagesTheWalletPublishesCarryTheTraceContextInTheirHeaders() {
        UUID userId = UUID.randomUUID();
        givenWallet(userId, "500000.00");

        publishRaw("wallet.commands", "debit", debitCommand(UUID.randomUUID(), UUID.randomUUID(), userId), Map.of());

        Message reply = receive("transfer.wallet-events.q", Duration.ofSeconds(10));
        assertThat(reply).isNotNull();
        assertThat((String) reply.getMessageProperties().getHeader("traceparent")).matches(TRACEPARENT);
        // The trace context stays out of the §4.1 envelope.
        assertThat(new String(reply.getBody(), StandardCharsets.UTF_8)).doesNotContain("traceparent");
    }

    @Test
    void ourListenersContinueTheTraceOfAnIncomingMessage(CapturedOutput output) {
        UUID userId = UUID.randomUUID();
        givenWallet(userId, "500000.00");
        UUID correlationId = UUID.randomUUID();
        String traceId = randomHex(32);
        String traceparent = "00-" + traceId + "-" + randomHex(16) + "-01";

        publishRaw("wallet.commands", "debit", debitCommand(correlationId, UUID.randomUUID(), userId),
                Map.of("traceparent", traceparent));

        String ownLine = "DebitWallet applied: version=1 correlationId=" + correlationId;
        await("debit log line", Duration.ofSeconds(10), () -> output.getOut().contains(ownLine));
        assertThat(lineContaining(output, ownLine)).containsPattern("\\[" + traceId + ",[0-9a-f]{16}\\] ");
    }

    @Test
    void logLinesEndWithTheTraceAfterCorrelationAndTransfer(CapturedOutput output) {
        String correlationId = UUID.randomUUID().toString();
        String traceId = randomHex(32);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId);
        headers.set("traceparent", "00-" + traceId + "-" + randomHex(16) + "-01");

        // No X-User-Id: the error handler logs an ERROR line while the request is in progress.
        http.exchange("/wallets/me/balance", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(lineContaining(output, "the Gateway must inject it"))
                .containsPattern("\\[" + correlationId + "\\] \\[\\] \\[" + traceId + ",[0-9a-f]{16}\\] ");
    }

    @Test
    void anUnreachableCollectorNeverSlowsRequestsDown() {
        UUID userId = UUID.randomUUID();
        givenWallet(userId, "100000.00");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId.toString());

        // Warm-up: the first request of a fresh context pays for class loading and the first connections.
        http.exchange("/wallets/me/balance", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // 60 requests over ~12 s: the span exporter tries (and fails) to send a batch every 5 s meanwhile.
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long start = System.nanoTime();
            ResponseEntity<String> response = http.exchange("/wallets/me/balance", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            millis.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            pause(Duration.ofMillis(200));
        }

        assertThat(millis).as("request times in ms").allMatch(ms -> ms < 1000);
    }

    /** Publishes straight on the channel, so the test's RabbitTemplate cannot add or replace the trace headers. */
    private void publishRaw(String exchange, String routingKey, String[] message, Map<String, Object> headers) {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .messageId(message[0])
                .contentType("application/json")
                .headers(headers)
                .build();
        rabbit.execute(channel -> {
            channel.basicPublish(exchange, routingKey, properties, message[1].getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    /** {messageId, envelope JSON} of a DebitWallet as Transfer sends it. */
    private static String[] debitCommand(UUID correlationId, UUID transferId, UUID userId) {
        String messageId = UUID.randomUUID().toString();
        return new String[] {messageId, envelope(messageId, "DebitWallet", correlationId, transferId,
                "transfer-service", debitWallet(transferId, userId, "1000.00"))};
    }

    private static String randomHex(int length) {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, length);
    }

    private static String lineContaining(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no log line contains: " + text));
    }
}

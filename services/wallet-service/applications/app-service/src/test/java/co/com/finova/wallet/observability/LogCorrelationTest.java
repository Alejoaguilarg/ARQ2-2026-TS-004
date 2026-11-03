package co.com.finova.wallet.observability;

import co.com.finova.wallet.messaging.MessagingTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1.4: correlationId and transferId in every log line, with the team log format (pending item 6):
 * "[correlationId] [transferId] " in the pattern, filled from the MDC.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class LogCorrelationTest extends MessagingTestBase {

    @Autowired
    private TestRestTemplate http;

    @Test
    void httpRequestLogLineCarriesTheGatewayCorrelationId(CapturedOutput output) {
        String correlationId = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId);

        // No X-User-Id: the error handler logs an ERROR line while the request is in progress.
        ResponseEntity<String> response = http.exchange("/wallets/me/balance", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(lineContaining(output, "the Gateway must inject it")).contains("[" + correlationId + "] [] ");
    }

    @Test
    void consumedMessageLogLineCarriesCorrelationAndTransferId(CapturedOutput output) {
        UUID correlationId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        String payload = """
                {"walletId":"%s","userId":"%s","available":"100.00","version":1}"""
                .formatted(UUID.randomUUID(), userId);
        // Other test classes share the broker and may project their own BalanceChanged at the same time, so the line
        // is matched by this message's userId, not by the first projection line.
        String ownLine = "BalanceChanged projected version=1 userId=" + userId;

        publish("wallet.events", "wallet.balance-changed", messageId,
                envelope(messageId, "BalanceChanged", correlationId, transferId, "wallet-service", payload));

        await("projection log line", Duration.ofSeconds(10), () -> output.getOut().contains(ownLine));
        assertThat(lineContaining(output, ownLine)).contains("[" + correlationId + "] [" + transferId + "] ");
    }

    private static String lineContaining(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no log line contains: " + text));
    }
}

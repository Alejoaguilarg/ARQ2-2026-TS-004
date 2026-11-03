package co.com.finova.wallet.observability;

import co.com.finova.wallet.messaging.MessagingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1.3: Prometheus scrapes /actuator/prometheus (infra/observability/prometheus/prometheus.yml).
 * The histogram is turned on the same way the compose does it (x-app-env, SPRING_APPLICATION_JSON), and the
 * metrics the Grafana panels query must be there. Spring Boot turns metric exporters off in tests by default,
 * so @AutoConfigureObservability turns them back on, as in production.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.metrics.distribution.percentiles-histogram.http.server.requests=true")
class PrometheusEndpointTest extends MessagingTestBase {

    @Autowired
    private TestRestTemplate http;

    @Test
    void prometheusEndpointExposesTheDashboardMetrics() {
        http.getForEntity("/actuator/health", String.class);

        ResponseEntity<String> response = http.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("http_server_requests_seconds_bucket")
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections_active");
    }
}

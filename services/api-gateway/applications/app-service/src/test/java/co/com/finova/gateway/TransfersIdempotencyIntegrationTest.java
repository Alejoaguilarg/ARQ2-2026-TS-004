package co.com.finova.gateway;

import co.com.finova.gateway.filters.AuthenticatedUser;
import co.com.finova.gateway.filters.IdempotencyFilter;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// Full path: servlet filter -> Spring Cloud Gateway proxy -> upstream, with a real Redis.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransfersIdempotencyIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final AtomicInteger TRANSFER_CALLS = new AtomicInteger();
    private static final AtomicReference<String> TRANSFER_BODY = new AtomicReference<>();
    private static final HttpServer TRANSFER = startTransferStub();
    private static final String BODY = "{\"recipientUserId\":\"r-1\",\"amount\":\"150000.00\",\"currency\":\"COP\"}";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("TRANSFER_SERVICE_URI", () -> "http://localhost:" + TRANSFER.getAddress().getPort());
    }

    @AfterAll
    static void stopTransfer() {
        TRANSFER.stop(0);
    }

    @Test
    void routesOnceAndReplaysTheSameTransfer() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = postTransfer(key, BODY);
        HttpResponse<String> retry = postTransfer(key, BODY);
        HttpResponse<String> reused = postTransfer(key, BODY.replace("150000.00", "1.00"));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(TRANSFER_BODY.get()).isEqualTo(BODY);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(TRANSFER_CALLS.get()).isOne();
    }

    private HttpResponse<String> postTransfer(String key, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Test-User", "7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startTransferStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/transfers", exchange -> {
                TRANSFER_CALLS.incrementAndGet();
                TRANSFER_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] answer = ("{\"transferId\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Stands in for the authentication filter (spec.md §3.1 step 1), which is not built yet.
    @TestConfiguration
    static class FakeAuthentication {

        @Bean
        FilterRegistrationBean<Filter> fakeAuthentication() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                String user = ((jakarta.servlet.http.HttpServletRequest) request).getHeader("X-Test-User");
                if (user != null) {
                    request.setAttribute(AuthenticatedUser.ATTRIBUTE, user);
                }
                chain.doFilter(request, response);
            });
            registration.setOrder(IdempotencyFilter.ORDER - 1);
            return registration;
        }
    }
}

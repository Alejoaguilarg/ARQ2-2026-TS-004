package co.com.finova.notification.consumer;

import co.com.finova.notification.model.exceptions.NotificationDeliveryException;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Locale;

/**
 * Driven adapter: hands notifications to the external provider over HTTP
 * ({@code POST /push}, {@code /email} or {@code /sms}). Any error answer (4xx/5xx), timeout or
 * connection failure becomes a {@link NotificationDeliveryException}, so the message is retried.
 */
public class HttpNotificationProvider implements NotificationProvider {

    private final RestClient restClient;

    public HttpNotificationProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void send(Notification notification) {
        String path = "/" + notification.channel().name().toLowerCase(Locale.ROOT);
        try {
            restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        // Lets a real provider discard a notification it already received (retries)
                        headers.set("Idempotency-Key", notification.notificationId().toString());
                        if (notification.correlationId() != null) {
                            headers.set("X-Correlation-Id", notification.correlationId());
                        }
                    })
                    .body(ProviderRequest.from(notification))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new NotificationDeliveryException("The provider did not accept notification "
                    + notification.notificationId() + " (" + notification.channel() + "): " + e.getMessage(), e);
        }
    }
}

package co.com.finova.notification.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection to the external Email / SMS / Push provider (a WireMock mock in Wave 2).
 * In Docker Compose, base-url comes from NOTIFICATION_PROVIDER_URI.
 */
@ConfigurationProperties(prefix = "adapters.notification-provider")
public record ProviderProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}

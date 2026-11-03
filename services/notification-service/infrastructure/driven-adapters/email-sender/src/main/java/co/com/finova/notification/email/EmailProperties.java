package co.com.finova.notification.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Real email delivery through SMTP (optional; disabled by default).
 *
 * @param enabled true to send EMAIL notifications through SMTP instead of the provider mock
 * @param from    sender address; with Gmail it must be the authenticated account
 */
@ConfigurationProperties(prefix = "adapters.email")
public record EmailProperties(boolean enabled, String from) {
}

package co.com.finova.notification.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Real email delivery through SMTP (optional; disabled by default).
 *
 * @param enabled    true to send EMAIL notifications through SMTP instead of the provider mock
 * @param from       sender address; with Gmail it must be the authenticated account
 * @param redirectTo demo inbox: when set, every email goes to this address instead of the recipient's,
 *                   and the body says who it was for. Empty sends to the real recipient.
 */
@ConfigurationProperties(prefix = "adapters.email")
public record EmailProperties(boolean enabled, String from, String redirectTo) {
}

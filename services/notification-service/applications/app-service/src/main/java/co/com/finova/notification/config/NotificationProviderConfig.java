package co.com.finova.notification.config;

import co.com.finova.notification.consumer.HttpNotificationProvider;
import co.com.finova.notification.email.SmtpEmailProvider;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.EnumMap;
import java.util.Map;

/**
 * Composition root of the NotificationProvider port. By default every channel goes to the HTTP
 * provider (the mock of spec §1.1). With adapters.email.enabled=true, EMAIL goes through SMTP.
 */
@Configuration
public class NotificationProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationProviderConfig.class);

    @Bean
    @Primary
    public NotificationProvider notificationProvider(HttpNotificationProvider httpProvider,
                                                     ObjectProvider<SmtpEmailProvider> smtpProvider) {
        Map<Channel, NotificationProvider> byChannel = new EnumMap<>(Channel.class);
        SmtpEmailProvider smtp = smtpProvider.getIfAvailable();
        if (smtp != null) {
            byChannel.put(Channel.EMAIL, smtp);
            log.info("EMAIL notifications are delivered through SMTP; PUSH and SMS through the HTTP provider");
        } else {
            log.info("All notifications are delivered through the HTTP provider");
        }
        return new RoutingNotificationProvider(httpProvider, byChannel);
    }
}

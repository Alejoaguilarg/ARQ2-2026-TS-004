package co.com.finova.notification.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Only active with adapters.email.enabled=true. Spring Boot creates the JavaMailSender from the
 * spring.mail.* properties (host, port, username, password), which come from environment variables.
 */
@Configuration
@ConditionalOnProperty(prefix = "adapters.email", name = "enabled", havingValue = "true")
public class EmailSenderConfig {

    @Bean
    public SmtpEmailProvider smtpEmailProvider(JavaMailSender mailSender, EmailProperties properties) {
        if (properties.from() == null || properties.from().isBlank()) {
            throw new IllegalStateException("adapters.email.from (or SPRING_MAIL_USERNAME) is required "
                    + "when adapters.email.enabled=true");
        }
        return new SmtpEmailProvider(mailSender, properties.from(), properties.redirectTo());
    }
}

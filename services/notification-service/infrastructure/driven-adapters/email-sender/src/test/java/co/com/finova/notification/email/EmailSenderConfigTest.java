package co.com.finova.notification.email;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

class EmailSenderConfigTest {

    private final EmailSenderConfig config = new EmailSenderConfig();
    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @Test
    void createsTheSmtpProviderWithTheSender() {
        assertThat(config.smtpEmailProvider(mailSender, new EmailProperties(true, "notificaciones@finova.test")))
                .isNotNull();
    }

    @Test
    void requiresASenderAddress() {
        assertThatIllegalStateException()
                .isThrownBy(() -> config.smtpEmailProvider(mailSender, new EmailProperties(true, " ")));
        assertThatIllegalStateException()
                .isThrownBy(() -> config.smtpEmailProvider(mailSender, new EmailProperties(true, null)));
    }
}

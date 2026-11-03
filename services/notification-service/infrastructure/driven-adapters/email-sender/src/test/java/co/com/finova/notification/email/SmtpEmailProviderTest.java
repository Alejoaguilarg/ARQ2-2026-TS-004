package co.com.finova.notification.email;

import co.com.finova.notification.model.exceptions.NotificationDeliveryException;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.TransferEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailProviderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailProvider provider = new SmtpEmailProvider(mailSender, "finova.pruebas@gmail.com");

    @Test
    void sendsAnEmailWithTheNotificationText() {
        provider.send(notification(Channel.EMAIL));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("finova.pruebas@gmail.com");
        assertThat(sent.getValue().getTo()).containsExactly("user.a@finova.test");
        assertThat(sent.getValue().getSubject()).isEqualTo("Finova: transfer sent");
        assertThat(sent.getValue().getText()).startsWith("You sent 150000.00 COP");
    }

    @Test
    void withADemoInboxEveryEmailGoesThereAndSaysWhoItWasFor() {
        new SmtpEmailProvider(mailSender, "finova.pruebas@gmail.com", " demo.inbox@finova.test ")
                .send(notification(Channel.EMAIL));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("demo.inbox@finova.test");
        assertThat(sent.getValue().getSubject()).isEqualTo("Finova: transfer sent");
        assertThat(sent.getValue().getText())
                .startsWith("You sent 150000.00 COP")
                .endsWith("(Demo inbox: this email was for user.a@finova.test)");
    }

    @Test
    void aBlankDemoInboxSendsToTheRecipient() {
        new SmtpEmailProvider(mailSender, "finova.pruebas@gmail.com", " ").send(notification(Channel.EMAIL));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("user.a@finova.test");
    }

    @Test
    void anSmtpFailureIsADeliveryFailure() {
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> provider.send(notification(Channel.EMAIL)))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void onlyDeliversEmail() {
        assertThatIllegalArgumentException().isThrownBy(() -> provider.send(notification(Channel.SMS)));
    }

    private static Notification notification(Channel channel) {
        return new Notification(UUID.randomUUID(), channel, "user.a@finova.test",
                TransferEventType.TRANSFER_COMPLETED, UUID.randomUUID(), "150000.00", "COP", null,
                "Finova: transfer sent", "You sent 150000.00 COP. The transfer was completed.",
                "corr-1");
    }
}

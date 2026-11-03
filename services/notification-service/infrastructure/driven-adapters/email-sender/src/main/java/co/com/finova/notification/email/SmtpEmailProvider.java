package co.com.finova.notification.email;

import co.com.finova.notification.model.exceptions.NotificationDeliveryException;
import co.com.finova.notification.model.notification.Channel;
import co.com.finova.notification.model.notification.Notification;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Driven adapter: delivers EMAIL notifications through a real SMTP server (for example Gmail with an
 * app password). It implements the same port as the HTTP provider, so the domain does not change.
 */
public class SmtpEmailProvider implements NotificationProvider {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailProvider(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(Notification notification) {
        if (notification.channel() != Channel.EMAIL) {
            throw new IllegalArgumentException("SmtpEmailProvider only delivers EMAIL notifications, got "
                    + notification.channel());
        }
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(from);
        email.setTo(notification.to());
        email.setSubject(notification.subject());
        email.setText(notification.message());
        try {
            mailSender.send(email);
        } catch (MailException e) {
            throw new NotificationDeliveryException("SMTP server did not accept notification "
                    + notification.notificationId() + ": " + e.getMessage(), e);
        }
    }
}

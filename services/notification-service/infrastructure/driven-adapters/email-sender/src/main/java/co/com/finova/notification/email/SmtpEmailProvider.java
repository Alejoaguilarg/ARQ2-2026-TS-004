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
    private final String redirectTo;

    public SmtpEmailProvider(JavaMailSender mailSender, String from) {
        this(mailSender, from, null);
    }

    /**
     * @param redirectTo demo inbox that receives every email (the demo users have example.com addresses,
     *                   which receive nothing); null or blank sends to the real recipient
     */
    public SmtpEmailProvider(JavaMailSender mailSender, String from, String redirectTo) {
        this.mailSender = mailSender;
        this.from = from;
        this.redirectTo = redirectTo == null || redirectTo.isBlank() ? null : redirectTo.trim();
    }

    @Override
    public void send(Notification notification) {
        if (notification.channel() != Channel.EMAIL) {
            throw new IllegalArgumentException("SmtpEmailProvider only delivers EMAIL notifications, got "
                    + notification.channel());
        }
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(from);
        if (redirectTo == null) {
            email.setTo(notification.to());
            email.setText(notification.message());
        } else {
            email.setTo(redirectTo);
            email.setText(notification.message() + "\n\n(Demo inbox: this email was for " + notification.to() + ")");
        }
        email.setSubject(notification.subject());
        try {
            mailSender.send(email);
        } catch (MailException e) {
            throw new NotificationDeliveryException("SMTP server did not accept notification "
                    + notification.notificationId() + ": " + e.getMessage(), e);
        }
    }
}

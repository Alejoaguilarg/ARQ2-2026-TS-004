package co.com.finova.wallet.mq.sender;

import co.com.bancolombia.commons.jms.api.MQMessageSenderSync;
import co.com.bancolombia.commons.jms.mq.EnableMQGateway;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.jms.Message;
import java.util.concurrent.TimeUnit;

@Component
@AllArgsConstructor
@EnableMQGateway(scanBasePackages = "co.com.finova.wallet")
public class SampleMQMessageSender /* implements SomeGateway */ {
    private final MQMessageSenderSync sender;
    private final Timer timer = Metrics.timer("mq_send_message", "operation", "my-operation"); // TODO: Change operation name

    public String send(String message) {
        long start = System.currentTimeMillis();
        String messageId = sender.send(context -> {
            Message textMessage = context.createTextMessage(message);
//            textMessage.setJMSReplyTo(container.get("any-custom-value")); // Inject the reply to queue from container
            return textMessage;
        });
        timer.record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
        return messageId;
    }
}

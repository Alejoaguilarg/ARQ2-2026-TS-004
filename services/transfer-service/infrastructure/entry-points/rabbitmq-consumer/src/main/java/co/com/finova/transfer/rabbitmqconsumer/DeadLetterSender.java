package co.com.finova.transfer.rabbitmqconsumer;

import org.springframework.amqp.core.Message;

/** Puts a message in its DLQ (spec §4.2). Returns only after the broker confirmed it; throws otherwise. */
public interface DeadLetterSender {

    void send(Message message, String deadLetterQueue);
}

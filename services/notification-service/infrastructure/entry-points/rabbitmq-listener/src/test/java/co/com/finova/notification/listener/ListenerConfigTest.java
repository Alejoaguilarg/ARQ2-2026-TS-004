package co.com.finova.notification.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ListenerConfigTest {

    private final ListenerConfig config = new ListenerConfig();

    @Test
    void createsTheMapperAndTheDeadLetterPublisher() {
        ListenerProperties properties = new ListenerProperties("q", "q.dlq", 3, Duration.ofSeconds(5));

        assertThat(config.transferEventMapper(new ObjectMapper())).isNotNull();
        assertThat(config.deadLetterPublisher(mock(RabbitTemplate.class), properties)).isNotNull();
    }
}

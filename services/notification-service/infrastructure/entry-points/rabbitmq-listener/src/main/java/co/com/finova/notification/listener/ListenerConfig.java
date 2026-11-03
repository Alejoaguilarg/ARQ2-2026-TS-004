package co.com.finova.notification.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Only consumes: no Queue or Exchange beans are declared here, because the topology is created by
 * rabbitmq-init from infra/rabbitmq/definitions.json (declaring it again could conflict with it).
 */
@Configuration
public class ListenerConfig {

    @Bean
    public TransferEventMapper transferEventMapper(ObjectMapper objectMapper) {
        return new TransferEventMapper(objectMapper);
    }

    @Bean
    public DeadLetterPublisher deadLetterPublisher(RabbitTemplate rabbitTemplate, ListenerProperties properties) {
        return new DeadLetterPublisher(rabbitTemplate, properties);
    }
}

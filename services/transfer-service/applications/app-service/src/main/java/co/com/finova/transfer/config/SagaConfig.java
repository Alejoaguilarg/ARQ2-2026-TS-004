package co.com.finova.transfer.config;

import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.outbox.gateways.OutboxRepository;
import co.com.finova.transfer.usecase.saga.SagaOutbox;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What the saga use cases share and UseCasesConfig does not pick up (its name does not end in UseCase). */
@Configuration
public class SagaConfig {

    @Bean
    public SagaOutbox sagaOutbox(OutboxRepository outbox, IdGenerator ids) {
        return new SagaOutbox(outbox, ids);
    }
}

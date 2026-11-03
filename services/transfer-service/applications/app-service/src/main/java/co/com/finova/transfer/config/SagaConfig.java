package co.com.finova.transfer.config;

import co.com.finova.transfer.model.common.gateways.IdGenerator;
import co.com.finova.transfer.model.outbox.gateways.OutboxRepository;
import co.com.finova.transfer.usecase.saga.SagaOutbox;
import co.com.finova.transfer.usecase.sweeptimeouts.SagaTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** What the saga use cases share and UseCasesConfig does not pick up (its name does not end in UseCase). */
@Configuration
public class SagaConfig {

    @Bean
    public SagaOutbox sagaOutbox(OutboxRepository outbox, IdGenerator ids) {
        return new SagaOutbox(outbox, ids);
    }

    /** Spec §4.6 timeouts; the defaults are the spec values, shorter ones are for tests only. */
    @Bean
    public SagaTimeouts sagaTimeouts(@Value("${adapters.saga.pending-timeout:30s}") Duration pending,
                                     @Value("${adapters.saga.credit-timeout:10s}") Duration credit,
                                     @Value("${adapters.saga.reverse-resend:30s}") Duration reverseResend) {
        return new SagaTimeouts(pending, credit, reverseResend);
    }
}

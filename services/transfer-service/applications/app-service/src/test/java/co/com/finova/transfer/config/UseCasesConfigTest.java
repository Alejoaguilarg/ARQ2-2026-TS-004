package co.com.finova.transfer.config;

import co.com.finova.transfer.model.common.gateways.TransactionRunner;
import co.com.finova.transfer.model.message.gateways.MessagePublisher;
import co.com.finova.transfer.model.outbox.gateways.OutboxRelayRepository;
import co.com.finova.transfer.model.outbox.gateways.OutboxRepository;
import co.com.finova.transfer.model.processedmessage.gateways.ProcessedMessageRepository;
import co.com.finova.transfer.model.transfer.gateways.TransferRepository;
import co.com.finova.transfer.usecase.createtransfer.CreateTransferUseCase;
import co.com.finova.transfer.usecase.gettransfer.GetTransferUseCase;
import co.com.finova.transfer.usecase.handlewalletresult.HandleWalletResultUseCase;
import co.com.finova.transfer.usecase.relayoutbox.RelayOutboxUseCase;
import co.com.finova.transfer.usecase.saga.SagaOutbox;
import co.com.finova.transfer.usecase.sweeptimeouts.SagaTimeouts;
import co.com.finova.transfer.usecase.sweeptimeouts.SweepTimeoutsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Every use case and the saga outbox are beans, built from the ports the adapters provide. */
class UseCasesConfigTest {

    @Test
    void registersTheUseCasesAndTheirSharedPieces() {
        try (var context = new AnnotationConfigApplicationContext()) {
            // Spring Boot's conversions, so "30s" in @Value becomes a Duration as in the running service.
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.register(Ports.class);
            context.refresh();
            assertThat(context.getBean(CreateTransferUseCase.class)).isNotNull();
            assertThat(context.getBean(HandleWalletResultUseCase.class)).isNotNull();
            assertThat(context.getBean(GetTransferUseCase.class)).isNotNull();
            assertThat(context.getBean(RelayOutboxUseCase.class)).isNotNull();
            assertThat(context.getBean(SweepTimeoutsUseCase.class)).isNotNull();
            assertThat(context.getBean(SagaOutbox.class)).isNotNull();
            assertThat(context.getBean(SagaTimeouts.class)).isEqualTo(SagaTimeouts.SPEC);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        }
    }

    @Configuration
    @Import({UseCasesConfig.class, SagaConfig.class, ClockConfig.class})
    static class Ports {

        @Bean
        TransferRepository transferRepository() {
            return mock(TransferRepository.class);
        }

        @Bean
        OutboxRepository outboxRepository() {
            return mock(OutboxRepository.class);
        }

        @Bean
        OutboxRelayRepository outboxRelayRepository() {
            return mock(OutboxRelayRepository.class);
        }

        @Bean
        MessagePublisher messagePublisher() {
            return mock(MessagePublisher.class);
        }

        @Bean
        ProcessedMessageRepository processedMessageRepository() {
            return mock(ProcessedMessageRepository.class);
        }

        @Bean
        TransactionRunner transactionRunner() {
            return mock(TransactionRunner.class);
        }
    }
}

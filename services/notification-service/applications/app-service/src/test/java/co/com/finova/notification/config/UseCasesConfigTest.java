package co.com.finova.notification.config;

import co.com.finova.notification.model.notification.gateways.NotificationLogRepository;
import co.com.finova.notification.model.notification.gateways.NotificationProvider;
import co.com.finova.notification.model.notification.gateways.RecipientContactRepository;
import co.com.finova.notification.usecase.notifytransferoutcome.NotifyTransferOutcomeUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UseCasesConfigTest {

    @Test
    void registersTheUseCaseWithItsPorts() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            assertThat(context.getBean(NotifyTransferOutcomeUseCase.class)).isNotNull();
        }
    }

    @Configuration
    @Import({UseCasesConfig.class, ClockConfig.class})
    static class TestConfig {

        @Bean
        RecipientContactRepository recipientContactRepository() {
            return mock(RecipientContactRepository.class);
        }

        @Bean
        NotificationLogRepository notificationLogRepository() {
            return mock(NotificationLogRepository.class);
        }

        @Bean
        NotificationProvider notificationProvider() {
            return mock(NotificationProvider.class);
        }
    }
}

package co.com.finova.identity.config;

import co.com.finova.identity.model.session.gateways.RevocationStore;
import co.com.finova.identity.usecase.session.RevokeSessionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UseCasesConfigTest {

    @Test
    void registersEveryUseCaseAsABean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            assertThat(context.getBean(RevokeSessionUseCase.class)).isNotNull();
        }
    }

    @Configuration
    @Import(UseCasesConfig.class)
    static class TestConfig {

        @Bean
        RevocationStore revocationStore() {
            return mock(RevocationStore.class);
        }
    }
}

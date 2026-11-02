package co.com.finova.gateway.config;

import co.com.finova.gateway.model.idempotency.gateways.IdempotencyStore;
import co.com.finova.gateway.model.security.gateways.CounterStore;
import co.com.finova.gateway.usecase.idempotency.IdempotencyUseCase;
import co.com.finova.gateway.usecase.security.LockoutUseCase;
import co.com.finova.gateway.usecase.security.RateLimitUseCase;
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
            assertThat(context.getBean(IdempotencyUseCase.class)).isNotNull();
            assertThat(context.getBean(RateLimitUseCase.class)).isNotNull();
            assertThat(context.getBean(LockoutUseCase.class)).isNotNull();
        }
    }

    @Configuration
    @Import(UseCasesConfig.class)
    static class TestConfig {

        @Bean
        IdempotencyStore idempotencyStore() {
            return mock(IdempotencyStore.class);
        }

        @Bean
        CounterStore counterStore() {
            return mock(CounterStore.class);
        }
    }
}

package co.com.finova.wallet.config;

import co.com.finova.wallet.model.balance.gateways.BalanceCache;
import co.com.finova.wallet.model.balance.gateways.WalletRepository;
import co.com.finova.wallet.usecase.balance.GetBalanceUseCase;
import co.com.finova.wallet.usecase.balance.UpdateBalanceProjectionUseCase;
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
            assertThat(context.getBean(GetBalanceUseCase.class)).isNotNull();
            assertThat(context.getBean(UpdateBalanceProjectionUseCase.class)).isNotNull();
        }
    }

    @Configuration
    @Import(UseCasesConfig.class)
    static class TestConfig {

        @Bean
        BalanceCache balanceCache() {
            return mock(BalanceCache.class);
        }

        @Bean
        WalletRepository walletRepository() {
            return mock(WalletRepository.class);
        }
    }
}

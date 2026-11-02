package co.com.finova.identity.config;

import co.com.finova.identity.model.session.gateways.RevocationStore;
import co.com.finova.identity.model.session.gateways.DeviceRepository;
import co.com.finova.identity.model.session.gateways.RefreshTokenRepository;
import co.com.finova.identity.model.token.gateways.TokenSigner;
import co.com.finova.identity.model.user.gateways.CredentialVerifier;
import co.com.finova.identity.model.user.gateways.UserRepository;
import co.com.finova.identity.usecase.session.LoginUseCase;
import co.com.finova.identity.usecase.session.RefreshSessionUseCase;
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
            assertThat(context.getBean(LoginUseCase.class)).isNotNull();
            assertThat(context.getBean(RefreshSessionUseCase.class)).isNotNull();
        }
    }

    @Configuration
    @Import(UseCasesConfig.class)
    static class TestConfig {

        @Bean
        RevocationStore revocationStore() {
            return mock(RevocationStore.class);
        }

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        CredentialVerifier credentialVerifier() {
            return mock(CredentialVerifier.class);
        }

        @Bean
        DeviceRepository deviceRepository() {
            return mock(DeviceRepository.class);
        }

        @Bean
        RefreshTokenRepository refreshTokenRepository() {
            return mock(RefreshTokenRepository.class);
        }

        @Bean
        TokenSigner tokenSigner() {
            return mock(TokenSigner.class);
        }
    }
}

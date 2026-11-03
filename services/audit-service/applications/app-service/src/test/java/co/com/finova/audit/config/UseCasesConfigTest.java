package co.com.finova.audit.config;

import co.com.finova.audit.model.common.gateways.TransactionRunner;
import co.com.finova.audit.model.record.gateways.AuditRecordRepository;
import co.com.finova.audit.usecase.queryrecords.QueryAuditRecordsUseCase;
import co.com.finova.audit.usecase.recordattempt.RecordAttemptUseCase;
import co.com.finova.audit.usecase.verifychain.VerifyAuditChainUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UseCasesConfigTest {

    @Test
    void registersEveryUseCase() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            assertThat(context.getBean(RecordAttemptUseCase.class)).isNotNull();
            assertThat(context.getBean(QueryAuditRecordsUseCase.class)).isNotNull();
            assertThat(context.getBean(VerifyAuditChainUseCase.class)).isNotNull();
        }
    }

    // The ports the use cases need; the real adapters live in the infrastructure modules.
    @Configuration
    @Import({UseCasesConfig.class, ClockConfig.class})
    static class TestConfig {

        @Bean
        AuditRecordRepository auditRecordRepository() {
            return mock(AuditRecordRepository.class);
        }

        @Bean
        TransactionRunner transactionRunner() {
            return mock(TransactionRunner.class);
        }
    }
}

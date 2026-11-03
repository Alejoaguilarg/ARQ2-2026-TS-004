package co.com.finova.wallet.config;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The broker connection comes only from environment variables (CLAUDE.md, pending items 3 and 26): the compose maps
 * RABBITMQ_HOST/PORT/USER/PASSWORD/VHOST/SSL_ENABLED from .env to the SPRING_RABBITMQ_* variables below, with the
 * local broker as the default. These tests build the connection factory without connecting to any broker; the
 * values are fictitious.
 */
class RabbitConnectionSettingsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class));

    @Test
    void cloudBrokerSettingsComeFromTheEnvironment() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        // The "-systemEnvironment" suffix makes Spring bind these names as real environment variables.
                        new SystemEnvironmentPropertySource("test-systemEnvironment", Map.of(
                                "SPRING_RABBITMQ_HOST", "broker.example.test",
                                "SPRING_RABBITMQ_PORT", "5671",
                                "SPRING_RABBITMQ_USERNAME", "wallet-test-user",
                                "SPRING_RABBITMQ_PASSWORD", "not-a-real-password",
                                "SPRING_RABBITMQ_VIRTUAL_HOST", "team-vhost",
                                "SPRING_RABBITMQ_SSL_ENABLED", "true"))))
                .run(context -> {
                    CachingConnectionFactory factory = context.getBean(CachingConnectionFactory.class);

                    assertThat(factory.getHost()).isEqualTo("broker.example.test");
                    assertThat(factory.getPort()).isEqualTo(5671);
                    assertThat(factory.getUsername()).isEqualTo("wallet-test-user");
                    assertThat(factory.getVirtualHost()).isEqualTo("team-vhost");
                    assertThat(factory.getRabbitConnectionFactory().isSSL()).isTrue();
                });
    }

    @Test
    void withoutCloudSettingsTheConnectionIsLocalAndPlain() {
        runner.run(context -> {
            CachingConnectionFactory factory = context.getBean(CachingConnectionFactory.class);

            assertThat(factory.getHost()).isEqualTo("localhost");
            assertThat(factory.getPort()).isEqualTo(5672);
            assertThat(factory.getVirtualHost()).isEqualTo("/");
            assertThat(factory.getRabbitConnectionFactory().isSSL()).isFalse();
        });
    }

    @Test
    void theWalletNeverBuildsItsOwnConnectionFactory() {
        JavaClasses walletClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("co.com.finova.wallet");

        // A hand-made factory would ignore the SPRING_RABBITMQ_* variables (host, vhost, TLS).
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("com.rabbitmq.client.ConnectionFactory")
                .orShould().callConstructor(CachingConnectionFactory.class)
                .because("the broker connection must come only from the SPRING_RABBITMQ_* environment variables")
                .check(walletClasses);
    }
}

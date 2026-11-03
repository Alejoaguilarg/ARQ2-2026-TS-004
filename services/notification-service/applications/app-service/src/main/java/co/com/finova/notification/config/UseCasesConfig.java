package co.com.finova.notification.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Registers every class whose name ends in "UseCase" as a bean, so the use cases stay free of
 * Spring annotations (same convention as the other services).
 */
@Configuration
@ComponentScan(basePackages = "co.com.finova.notification.usecase",
        includeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "^.+UseCase$")
        },
        useDefaultFilters = false)
public class UseCasesConfig {
}

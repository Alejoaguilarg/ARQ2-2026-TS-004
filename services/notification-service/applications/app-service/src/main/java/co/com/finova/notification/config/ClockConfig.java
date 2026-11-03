package co.com.finova.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Timestamps are UTC (spec §1.5). Injected so tests can use a fixed clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

package co.com.finova.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Time for the use cases and adapters: UTC (spec §1.5). Scheduling is on for the monthly partition
 * maintenance of audit_records.
 */
@Configuration
@EnableScheduling
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

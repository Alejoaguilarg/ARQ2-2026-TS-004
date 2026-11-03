package co.com.finova.wallet.config;

import co.com.finova.wallet.model.common.gateways.IdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

/** Time and identifiers for the use cases: UTC timestamps (spec §1.5) and UUID v4 ids. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IdGenerator idGenerator() {
        return UUID::randomUUID;
    }
}

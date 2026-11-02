package co.com.finova.wallet.jpa;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

// No component scan, so the slice test does not load JpaConfig's production DataSource.
@SpringBootConfiguration
@EnableAutoConfiguration
class JpaTestApplication {
}

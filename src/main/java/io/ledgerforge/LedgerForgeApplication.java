package io.ledgerforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// No username/password users exist: authentication is API keys (tenants) and an admin token.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class LedgerForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerForgeApplication.class, args);
    }
}

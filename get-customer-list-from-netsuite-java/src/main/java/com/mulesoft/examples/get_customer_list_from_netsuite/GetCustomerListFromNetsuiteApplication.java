package com.mulesoft.examples.get_customer_list_from_netsuite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point for the get-customer-list-from-netsuite example.
 *
 * <p>Component scanning starts at this package and covers its subpackages. The properties scan
 * registers every {@code @ConfigurationProperties} type in the same packages. Spring Security's
 * servlet auto-configuration and Boot's OAuth2 client auto-configuration are excluded: the
 * application has no security filter chain, no generated user and no property-bound client
 * registration (D-345).
 */
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class})
@ConfigurationPropertiesScan
public class GetCustomerListFromNetsuiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(GetCustomerListFromNetsuiteApplication.class, args);
    }
}

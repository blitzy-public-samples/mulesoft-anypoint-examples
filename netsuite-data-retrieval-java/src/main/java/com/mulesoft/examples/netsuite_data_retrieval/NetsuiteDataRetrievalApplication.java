package com.mulesoft.examples.netsuite_data_retrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the NetSuite data retrieval API on the {@code listener.netsuite-api-http-listener-config}
 * address (D-016), unauthenticated, with no Spring Security servlet or OAuth 2.0 client
 * auto-configuration; the excluded classes are the {@code spring.autoconfigure.exclude} list of
 * {@code application.yml}, and the {@code @ConfigurationProperties} classes of this package and its
 * subpackages are registered by the properties scan (D-097, D-288).
 */
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class})
@ConfigurationPropertiesScan
public class NetsuiteDataRetrievalApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetsuiteDataRetrievalApplication.class, args);
    }
}

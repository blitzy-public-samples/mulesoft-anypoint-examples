package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Box OAuth 2.0 authorization-code example. Starts the Spring Boot application that replaces
 * {@code http-authorization-code-web.xml}, the only configuration the original loads through
 * {@code config.resources} [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/mule-deploy.properties:5].
 * The class name and package follow D-003.
 *
 * <p>Component scanning from this package registers the beans of the {@code config}, {@code client},
 * {@code service}, {@code controller} and {@code exception} subpackages. The properties scan registers and binds
 * every {@code @ConfigurationProperties} type in this package and its subpackages, among them the
 * {@code config.BoxProperties} record of the {@code box.*} keys (D-518). The class enables no scheduling,
 * asynchronous execution, caching or JMS listeners, and declares no bean.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Oauth2AuthorizationCodeUsingTheHttpConnectorApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(Oauth2AuthorizationCodeUsingTheHttpConnectorApplication.class, args);
    }
}

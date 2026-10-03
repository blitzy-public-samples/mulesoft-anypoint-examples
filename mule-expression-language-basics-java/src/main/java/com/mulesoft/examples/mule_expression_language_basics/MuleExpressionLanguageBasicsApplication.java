package com.mulesoft.examples.mule_expression_language_basics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the mule-expression-language-basics Spring Boot application, which serves the six greeting
 * flows on {@code /greet1} … {@code /greet6} on embedded Undertow (D-010), bound to
 * {@code http.port} (default 8081, D-065) on {@code listener.http-listener-configuration.host}
 * (default {@code 0.0.0.0}).
 *
 * <p>Boot's error MVC configuration is excluded: the context registers no error controller, no
 * container error page and no {@code /error} mapping. {@code /error} is not routed and is answered
 * like every other path no listener owns, with 404 and {@code No listener for endpoint: /error}
 * (D-485). The properties scan registers every {@code @ConfigurationProperties} type in this package
 * and its subpackages, among them the {@code mel.output-path} binding (D-485).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
@ConfigurationPropertiesScan
public class MuleExpressionLanguageBasicsApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(MuleExpressionLanguageBasicsApplication.class, args);
    }
}

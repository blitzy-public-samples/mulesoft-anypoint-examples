package com.mulesoft.examples.scatter_gather_flow_control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the scatter-gather-flow-control Spring Boot application on embedded Undertow (D-010); it
 * replaces the Mule deployment of {@code scatter-gather.xml}, whose flow {@code scatter-gatherFlow}
 * listens on {@code /scatterGather}.
 *
 * <p>Boot's error MVC configuration is excluded: the context registers no error controller, no
 * container error page and no {@code /error} mapping, and {@code /error} is an unmatched path like
 * every other path without a handler (D-470). The properties scan registers every
 * {@code @ConfigurationProperties} type in this package and its subpackages. No scheduling,
 * asynchronous-method, caching or JMS support is enabled.
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ScatterGatherFlowControlApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ScatterGatherFlowControlApplication.class, args);
    }
}

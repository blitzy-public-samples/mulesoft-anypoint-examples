package com.mulesoft.examples.foreach_processing_and_choice_routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.webservices.WebServicesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the foreach-processing-and-choice-routing application: the loan broker on listener 1 for
 * every path, plus the SOAP credit agency and the five SOAP banks, each on its own port (D-011).
 * Boot's error controller and Spring WS auto-configuration are excluded: the context has no
 * {@code /error} mapping and no {@code MessageDispatcherServlet} at {@code /services/*}, and SOAP
 * requests reach only the project's own Spring WS dispatch (D-028).
 */
@SpringBootApplication(exclude = {ErrorMvcAutoConfiguration.class, WebServicesAutoConfiguration.class})
@ConfigurationPropertiesScan
public class ForeachProcessingAndChoiceRoutingApplication {

    /**
     * Starts the application context and its listeners.
     *
     * @param args command-line arguments, including {@code --key=value} property overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(ForeachProcessingAndChoiceRoutingApplication.class, args);
    }
}

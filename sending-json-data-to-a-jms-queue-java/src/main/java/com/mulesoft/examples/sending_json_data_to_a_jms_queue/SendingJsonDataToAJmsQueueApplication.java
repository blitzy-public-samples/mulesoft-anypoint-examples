package com.mulesoft.examples.sending_json_data_to_a_jms_queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.jms.annotation.EnableJms;

/**
 * Starts the Spring Boot application that serves {@code POST /sales} and sends each request body to the JMS
 * queue {@code sales} on the embedded Artemis broker (D-080). Replaces {@code mule-deploy.properties}
 * {@code config.resources=json-to-jms.xml}.
 *
 * <p>Boot's error MVC configuration is excluded: the context holds no {@code BasicErrorController}, no
 * container error page and no {@code /error} mapping, and {@code /error} answers the 404
 * {@code No listener for endpoint: /error} of {@code GlobalExceptionHandler.noListener} like every
 * other path except {@code /sales} (D-637).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
@EnableJms
public class SendingJsonDataToAJmsQueueApplication {

    /**
     * Starts the application context, the embedded Artemis broker and the Undertow listener.
     *
     * @param args command-line arguments passed to Spring Boot, for example {@code --http.port=9090}
     */
    public static void main(String[] args) {
        SpringApplication.run(SendingJsonDataToAJmsQueueApplication.class, args);
    }
}

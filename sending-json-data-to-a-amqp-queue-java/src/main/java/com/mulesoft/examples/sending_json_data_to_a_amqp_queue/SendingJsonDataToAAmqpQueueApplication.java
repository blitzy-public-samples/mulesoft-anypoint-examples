package com.mulesoft.examples.sending_json_data_to_a_amqp_queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;

/**
 * Starts the JSON-to-AMQP application.
 *
 * <p>Boot's error MVC configuration is excluded: the context registers no error controller, no
 * container error page and no {@code /error} mapping, and {@code /error} is answered 404 with body
 * {@code No listener for endpoint: /error} and no {@code Content-Type}, like every other unmatched
 * path (D-490).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
public class SendingJsonDataToAAmqpQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingJsonDataToAAmqpQueueApplication.class, args);
    }
}

package com.mulesoft.examples.sending_json_data_to_a_jms_queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

/**
 * Starts the Spring Boot application that serves {@code POST /sales} and sends each request body to the JMS
 * queue {@code sales} on the embedded Artemis broker (D-080). Replaces {@code mule-deploy.properties}
 * {@code config.resources=json-to-jms.xml}.
 */
@SpringBootApplication
@EnableJms
public class SendingJsonDataToAJmsQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingJsonDataToAJmsQueueApplication.class, args);
    }
}

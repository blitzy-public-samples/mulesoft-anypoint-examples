package com.mulesoft.examples.sending_json_data_to_a_jms_queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the JSON-to-JMS application. */
@SpringBootApplication
public class SendingJsonDataToAJmsQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingJsonDataToAJmsQueueApplication.class, args);
    }
}

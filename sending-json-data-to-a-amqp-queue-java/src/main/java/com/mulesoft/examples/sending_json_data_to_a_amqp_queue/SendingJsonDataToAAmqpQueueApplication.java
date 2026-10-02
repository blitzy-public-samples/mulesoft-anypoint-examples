package com.mulesoft.examples.sending_json_data_to_a_amqp_queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the JSON-to-AMQP application. */
@SpringBootApplication
public class SendingJsonDataToAAmqpQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingJsonDataToAAmqpQueueApplication.class, args);
    }
}

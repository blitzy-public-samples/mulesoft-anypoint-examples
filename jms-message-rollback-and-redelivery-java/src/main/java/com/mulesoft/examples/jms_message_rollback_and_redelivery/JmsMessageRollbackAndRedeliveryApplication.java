package com.mulesoft.examples.jms_message_rollback_and_redelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

/** Starts the application that consumes queue {@code in} transactionally and publishes to topic {@code topic1}. */
@SpringBootApplication
@EnableJms
public class JmsMessageRollbackAndRedeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(JmsMessageRollbackAndRedeliveryApplication.class, args);
    }
}

package com.mulesoft.examples.websphere_mq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

/** Starts the Spring Boot application that moves messages between the WebSphere MQ queues {@code in} and {@code out} and serves the browser channel UI. */
@SpringBootApplication
@EnableJms
public class WebsphereMqApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebsphereMqApplication.class, args);
    }
}

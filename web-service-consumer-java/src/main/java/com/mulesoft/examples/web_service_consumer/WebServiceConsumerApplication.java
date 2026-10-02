package com.mulesoft.examples.web_service_consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the Spring Boot application that exposes the t-shirt order and inventory HTTP endpoints backed by the t-shirt SOAP service. */
@SpringBootApplication
public class WebServiceConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebServiceConsumerApplication.class, args);
    }
}

package com.mulesoft.examples.service_orchestration_and_choice_routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.jms.annotation.EnableJms;

/**
 * Starts the service-orchestration-and-choice-routing application.
 */
@SpringBootApplication
@EnableCaching
@EnableJms
public class ServiceOrchestrationAndChoiceRoutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceOrchestrationAndChoiceRoutingApplication.class, args);
    }
}

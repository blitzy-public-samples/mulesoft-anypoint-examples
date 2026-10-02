package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the processing-orders-with-dataweave-and-APIkit application with scheduled tasks enabled. */
@SpringBootApplication
@EnableScheduling
public class ProcessingOrdersWithDataweaveAndApikitApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessingOrdersWithDataweaveAndApikitApplication.class, args);
    }
}

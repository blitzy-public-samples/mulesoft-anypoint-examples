package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the Currency API and the OrderFlow file poller of the processing-orders-with-dataweave-and-APIkit
 * application, with scheduled tasks enabled and the {@code @ConfigurationProperties} records of this
 * project's packages registered by scanning (D-004).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ProcessingOrdersWithDataweaveAndApikitApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ProcessingOrdersWithDataweaveAndApikitApplication.class, args);
    }
}

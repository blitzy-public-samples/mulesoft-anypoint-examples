package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the salesforce-data-synchronization-using-watermarking-and-batch-processing Spring Boot
 * application with scheduling enabled and every {@code @ConfigurationProperties} class under this
 * package registered by the properties scan (D-311).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication {

    /**
     * Runs the application with the given command-line arguments.
     *
     * @param args command-line arguments handed to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication.class, args);
    }
}

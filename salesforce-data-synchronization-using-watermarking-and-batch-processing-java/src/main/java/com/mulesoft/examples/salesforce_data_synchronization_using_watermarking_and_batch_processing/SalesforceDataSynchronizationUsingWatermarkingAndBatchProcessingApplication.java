package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the salesforce-data-synchronization-using-watermarking-and-batch-processing Spring Boot application with scheduling enabled. */
@SpringBootApplication
@EnableScheduling
public class SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication.class, args);
    }
}

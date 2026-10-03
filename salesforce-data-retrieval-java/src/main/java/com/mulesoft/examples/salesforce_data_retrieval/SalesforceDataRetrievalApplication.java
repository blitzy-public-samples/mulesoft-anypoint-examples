package com.mulesoft.examples.salesforce_data_retrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the salesforce-data-retrieval Spring Boot application ({@code GET /} form,
 * {@code POST /} query). Every {@code @ConfigurationProperties} class in this package and its
 * subpackages is registered by the properties scan (D-247).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SalesforceDataRetrievalApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesforceDataRetrievalApplication.class, args);
    }
}

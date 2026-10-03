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

    /**
     * Sets the JDK system property {@code sun.net.http.retryPost} to {@code false}, which stops
     * {@code HttpURLConnection} from re-sending a Salesforce partner API POST whose response read
     * failed, then starts the application context (D-020, D-633).
     *
     * @param args command-line arguments passed to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        System.setProperty("sun.net.http.retryPost", "false");
        SpringApplication.run(SalesforceDataRetrievalApplication.class, args);
    }
}

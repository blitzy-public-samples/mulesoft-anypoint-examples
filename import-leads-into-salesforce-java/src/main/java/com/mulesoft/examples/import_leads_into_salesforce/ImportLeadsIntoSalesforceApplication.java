package com.mulesoft.examples.import_leads_into_salesforce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the import-leads-into-salesforce Spring Boot application: schedules the lead file poller
 * and registers every {@code @ConfigurationProperties} class in this package and its subpackages,
 * among them the {@code sfdc.*} binding of the Salesforce connection (D-284).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ImportLeadsIntoSalesforceApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, applied as Spring Boot properties such as
     *             {@code --sfdc.user=<user>}
     */
    public static void main(String[] args) {
        SpringApplication.run(ImportLeadsIntoSalesforceApplication.class, args);
    }
}

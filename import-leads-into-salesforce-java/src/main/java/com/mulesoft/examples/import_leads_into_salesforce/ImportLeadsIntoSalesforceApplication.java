package com.mulesoft.examples.import_leads_into_salesforce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the import-leads-into-salesforce Spring Boot application with scheduled file polling enabled. */
@SpringBootApplication
@EnableScheduling
public class ImportLeadsIntoSalesforceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportLeadsIntoSalesforceApplication.class, args);
    }
}

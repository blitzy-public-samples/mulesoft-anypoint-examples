package com.mulesoft.examples.import_contacts_into_salesforce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the import-contacts-into-salesforce Spring Boot application with scheduled file polling enabled. */
@SpringBootApplication
@EnableScheduling
public class ImportContactsIntoSalesforceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportContactsIntoSalesforceApplication.class, args);
    }
}

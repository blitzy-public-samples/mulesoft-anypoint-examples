package com.mulesoft.examples.import_contacts_into_ms_dynamics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the import-contacts-into-ms-dynamics Spring Boot application with scheduled file polling enabled. */
@SpringBootApplication
@EnableScheduling
public class ImportContactsIntoMsDynamicsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportContactsIntoMsDynamicsApplication.class, args);
    }
}

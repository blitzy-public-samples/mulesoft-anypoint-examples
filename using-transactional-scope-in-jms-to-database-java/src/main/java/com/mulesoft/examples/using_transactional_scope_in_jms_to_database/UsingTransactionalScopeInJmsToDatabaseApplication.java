package com.mulesoft.examples.using_transactional_scope_in_jms_to_database;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

/** Starts the Spring Boot application that consumes orders from the JMS queue {@code in} and inserts them into the database in one transaction. */
@SpringBootApplication
@EnableJms
public class UsingTransactionalScopeInJmsToDatabaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsingTransactionalScopeInJmsToDatabaseApplication.class, args);
    }
}

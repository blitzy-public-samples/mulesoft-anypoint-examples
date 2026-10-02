package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the salesforce-to-MySQL-DB-using-Batch-Processing Spring Boot application with scheduling enabled. */
@SpringBootApplication
@EnableScheduling
public class SalesforceToMysqlDbUsingBatchProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesforceToMysqlDbUsingBatchProcessingApplication.class, args);
    }
}

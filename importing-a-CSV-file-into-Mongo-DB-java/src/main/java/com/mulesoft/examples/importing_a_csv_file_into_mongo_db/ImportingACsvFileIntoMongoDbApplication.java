package com.mulesoft.examples.importing_a_csv_file_into_mongo_db;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the application that polls CSV files and imports their rows into MongoDB. */
@SpringBootApplication
@EnableScheduling
public class ImportingACsvFileIntoMongoDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingACsvFileIntoMongoDbApplication.class, args);
    }
}

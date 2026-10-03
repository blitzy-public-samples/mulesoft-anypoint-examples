package com.mulesoft.examples.importing_a_csv_file_into_mongo_db;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the importing-a-CSV-file-into-Mongo-DB application, which polls CSV files and imports
 * their rows into MongoDB. Scheduled tasks are enabled for the CSV file poller, and every
 * {@code @ConfigurationProperties} class in this package and its subpackages is registered by
 * the properties scan (D-299).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ImportingACsvFileIntoMongoDbApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ImportingACsvFileIntoMongoDbApplication.class, args);
    }
}

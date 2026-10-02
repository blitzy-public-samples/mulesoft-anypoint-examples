package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the application that polls CSV files and uploads them to a SharePoint folder. */
@SpringBootApplication
@EnableScheduling
public class ImportingACsvFileIntoMsSharepointApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingACsvFileIntoMsSharepointApplication.class, args);
    }
}

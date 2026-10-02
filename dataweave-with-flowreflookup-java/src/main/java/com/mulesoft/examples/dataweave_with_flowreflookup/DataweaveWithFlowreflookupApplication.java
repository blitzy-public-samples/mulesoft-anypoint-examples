package com.mulesoft.examples.dataweave_with_flowreflookup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the dataweave-with-flowreflookup application with scheduled tasks enabled. */
@SpringBootApplication
@EnableScheduling
public class DataweaveWithFlowreflookupApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataweaveWithFlowreflookupApplication.class, args);
    }
}

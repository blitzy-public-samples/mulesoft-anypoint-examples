package com.mulesoft.examples.legacy_modernization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Starts the legacy-modernization application.
 */
@SpringBootApplication
@EnableAsync
public class LegacyModernizationApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyModernizationApplication.class, args);
    }
}

package com.mulesoft.examples.import_contacts_into_ms_dynamics;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.config.DynamicsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Boots the import-contacts-into-ms-dynamics application with the scheduled file poll (D-036) and the
 * {@code dynamics.*} property binding (D-517).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(DynamicsProperties.class)
public class ImportContactsIntoMsDynamicsApplication {

    /** Runs the application with the given command-line arguments. */
    public static void main(String[] args) {
        SpringApplication.run(ImportContactsIntoMsDynamicsApplication.class, args);
    }
}

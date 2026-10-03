package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the querying-a-db-and-attaching-results-to-an-email application.
 *
 * <p>{@link ConfigurationPropertiesScan} registers the {@code @ConfigurationProperties} records of this
 * package and its subpackages. No auto-configuration is excluded (D-328).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class QueryingADbAndAttachingResultsToAnEmailApplication {

    /**
     * Starts the Spring application context with the given command-line arguments.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(QueryingADbAndAttachingResultsToAnEmailApplication.class, args);
    }
}

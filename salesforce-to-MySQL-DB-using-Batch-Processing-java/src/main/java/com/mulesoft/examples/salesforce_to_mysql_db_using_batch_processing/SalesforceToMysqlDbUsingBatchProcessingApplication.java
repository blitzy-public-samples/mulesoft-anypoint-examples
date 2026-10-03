package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the salesforce-to-MySQL-DB-using-Batch-Processing Spring Boot application with scheduling enabled.
 *
 * <p>{@link EnableScheduling} registers the {@code @Scheduled} poller of flow {@code triggerFlow}, which feeds
 * batch job {@code salesforce-to-database-Batch}. {@link ConfigurationPropertiesScan} registers the
 * {@code @ConfigurationProperties} records of this package and its subpackages. The application opens no HTTP
 * listener: {@code application.yml} sets {@code spring.main.web-application-type} to {@code none}.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SalesforceToMysqlDbUsingBatchProcessingApplication {

    /**
     * Disables {@code HttpURLConnection} POST retries (D-020), then starts the Spring application context.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        System.setProperty("sun.net.http.retryPost", "false");
        SpringApplication.run(SalesforceToMysqlDbUsingBatchProcessingApplication.class, args);
    }
}

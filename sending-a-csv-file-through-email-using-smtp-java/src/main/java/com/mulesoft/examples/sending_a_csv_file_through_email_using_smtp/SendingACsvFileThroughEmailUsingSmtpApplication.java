package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of sending-a-csv-file-through-email-using-smtp-java, which runs the Java
 * implementation of the Mule flow {@code csv-to-smtpFlow} ({@code csv-to-smtp.xml}) with scheduled tasks
 * enabled and every {@code @ConfigurationProperties} class under this package registered by the properties
 * scan. Package layout: D-003; annotation set: D-382.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SendingACsvFileThroughEmailUsingSmtpApplication {

    /**
     * Starts the application context.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(SendingACsvFileThroughEmailUsingSmtpApplication.class, args);
    }
}

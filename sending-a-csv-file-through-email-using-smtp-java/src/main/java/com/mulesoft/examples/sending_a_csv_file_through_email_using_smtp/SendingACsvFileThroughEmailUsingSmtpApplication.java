package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the CSV-to-SMTP application with scheduled file polling enabled. */
@SpringBootApplication
@EnableScheduling
public class SendingACsvFileThroughEmailUsingSmtpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingACsvFileThroughEmailUsingSmtpApplication.class, args);
    }
}

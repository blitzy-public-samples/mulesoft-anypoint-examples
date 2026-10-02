package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the application that polls a POP3S mailbox and converts each email's first CSV attachment to XML. */
@SpringBootApplication
@EnableScheduling
public class ImportingAnEmailAttachmentUsingThePop3ConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingAnEmailAttachmentUsingThePop3ConnectorApplication.class, args);
    }
}

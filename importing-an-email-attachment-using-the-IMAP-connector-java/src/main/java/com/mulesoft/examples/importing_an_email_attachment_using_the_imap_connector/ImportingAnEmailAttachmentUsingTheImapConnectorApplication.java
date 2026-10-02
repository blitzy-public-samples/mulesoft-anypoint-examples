package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the application that polls an IMAPS mailbox and converts each email's first CSV attachment to XML. */
@SpringBootApplication
@EnableScheduling
public class ImportingAnEmailAttachmentUsingTheImapConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingAnEmailAttachmentUsingTheImapConnectorApplication.class, args);
    }
}

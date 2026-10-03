/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the IMAP attachment example; runs the scheduled IMAPS poller that
 * replaces flow {@code imap-to-csvFlow1} of {@code imap-to-xml.xml}, the single entry of
 * {@code config.resources} in the original {@code mule-deploy.properties} (D-004, D-297).
 *
 * <p>{@link EnableScheduling} runs the {@code @Scheduled} method of {@code scheduler.ImapAttachmentPoller},
 * which polls every {@code imap.check-frequency} milliseconds; the poller bean is registered only when
 * {@code polling.enabled} is {@code true}. {@link ConfigurationPropertiesScan} registers the
 * {@code @ConfigurationProperties} records of this package and its subpackages, among them
 * {@code config.MailStoreProperties}, bound from {@code imap.*}. The application starts no HTTP server;
 * while the poller is registered, its scheduler thread keeps the process running.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ImportingAnEmailAttachmentUsingTheImapConnectorApplication {

    /**
     * Starts the Spring application context with the given command-line arguments.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ImportingAnEmailAttachmentUsingTheImapConnectorApplication.class, args);
    }
}

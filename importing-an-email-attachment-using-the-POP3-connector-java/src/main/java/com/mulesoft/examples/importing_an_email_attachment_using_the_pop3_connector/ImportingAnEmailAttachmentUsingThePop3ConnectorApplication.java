package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the POP3 attachment-to-XML application with scheduling and configuration-properties
 * scanning enabled. The application replaces flow {@code pop-to-xmlFlow1} of {@code pop-to-xml.xml},
 * the single entry of {@code config.resources} in the original {@code mule-deploy.properties}
 * (D-321).
 *
 * <p>{@link EnableScheduling} runs the {@code @Scheduled} poll of
 * {@code scheduler.Pop3AttachmentPoller} every {@code pop3.check-frequency} milliseconds; the poller
 * bean is registered only when {@code polling.enabled} is {@code true} (D-093).
 * {@link ConfigurationPropertiesScan} registers the {@code @ConfigurationProperties} records of this
 * package and its subpackages, among them {@code config.MailStoreProperties}, bound from
 * {@code pop3.*}. The application starts no HTTP server; while the poller is registered, its
 * scheduler thread keeps the JVM running.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ImportingAnEmailAttachmentUsingThePop3ConnectorApplication {

    /**
     * Starts the Spring application context with the given command-line arguments.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ImportingAnEmailAttachmentUsingThePop3ConnectorApplication.class, args);
    }
}

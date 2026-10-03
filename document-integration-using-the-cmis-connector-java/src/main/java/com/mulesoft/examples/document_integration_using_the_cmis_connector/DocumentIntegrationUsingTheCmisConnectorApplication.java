package com.mulesoft.examples.document_integration_using_the_cmis_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the document-integration-using-the-cmis-connector application, which serves the {@code /cmis}
 * document upload endpoint. The properties scan registers every {@code @ConfigurationProperties} class in
 * this package and its subpackages (D-290).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DocumentIntegrationUsingTheCmisConnectorApplication {

    /**
     * Runs the Spring application context with the given command-line arguments.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(DocumentIntegrationUsingTheCmisConnectorApplication.class, args);
    }
}

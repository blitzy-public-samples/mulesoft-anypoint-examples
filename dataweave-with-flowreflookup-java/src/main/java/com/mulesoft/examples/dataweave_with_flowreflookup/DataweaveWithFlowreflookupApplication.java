package com.mulesoft.examples.dataweave_with_flowreflookup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the dataweave-with-flowreflookup application, the entry point of the converted
 * {@code dataweave-with-flowref.xml} configuration, with scheduled tasks enabled for the
 * companies file poller. Every {@code @ConfigurationProperties} class in this package and its
 * subpackages is registered by the properties scan (D-298).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class DataweaveWithFlowreflookupApplication {

    /**
     * Sets the JDK system property {@code sun.net.http.retryPost} to {@code false}, which stops
     * {@code HttpURLConnection} from re-sending a failed POST, then starts the application context
     * (D-020, D-298).
     *
     * @param args command-line arguments passed to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        System.setProperty("sun.net.http.retryPost", "false");
        SpringApplication.run(DataweaveWithFlowreflookupApplication.class, args);
    }
}

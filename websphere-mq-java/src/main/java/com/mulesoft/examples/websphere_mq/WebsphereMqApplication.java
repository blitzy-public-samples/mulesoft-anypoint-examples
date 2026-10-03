package com.mulesoft.examples.websphere_mq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.jms.annotation.EnableJms;

/**
 * Spring Boot application of the WebSphere MQ example: the docroot and the AJAX channels
 * {@code /services/wmqExample/enqueue} and {@code /services/wmqExample/dequeue} on {@code ajax-server.port},
 * and JMS listeners on the WMQ queues {@code in} and {@code out} (D-027, D-040).
 *
 * <p>{@link EnableJms} registers the {@code @JmsListener} methods of the application.
 * {@link ConfigurationPropertiesScan} registers the {@code @ConfigurationProperties} records of this package
 * and its subpackages, among them {@code config.WmqProperties}, bound from the {@code wmq.*} keys.
 */
@SpringBootApplication
@EnableJms
@ConfigurationPropertiesScan
public class WebsphereMqApplication {

    /**
     * Starts the Spring application context with the given command-line arguments.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(WebsphereMqApplication.class, args);
    }
}

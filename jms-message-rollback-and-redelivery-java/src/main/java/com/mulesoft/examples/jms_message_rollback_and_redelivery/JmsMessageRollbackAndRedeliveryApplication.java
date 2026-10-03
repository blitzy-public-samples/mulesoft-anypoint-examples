package com.mulesoft.examples.jms_message_rollback_and_redelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.jms.annotation.EnableJms;

/**
 * Spring Boot entry point of the jms-message-rollback-and-redelivery example. Starts the transacted JMS
 * listener for flow {@code JMSRedeliver}, defined in {@code listener.RedeliveryListener}, which consumes
 * queue {@code in} ({@code jms-redeliver.queue}) and publishes to topic {@code topic1}
 * ({@code jms-redeliver.topic}).
 *
 * <p>{@link EnableJms} registers every {@code @JmsListener} method of the application.
 * {@link ConfigurationPropertiesScan} registers the {@code @ConfigurationProperties} records of this
 * package and its subpackages, among them {@code config.RedeliveryDelayPolicy}, bound from
 * {@code redelivery-policy.*}: the listener-side redelivery delays and ceiling (D-024). The broker
 * connection is configured by the {@code spring.artemis.*} keys of {@code application.yml}.
 */
@SpringBootApplication
@EnableJms
@ConfigurationPropertiesScan
public class JmsMessageRollbackAndRedeliveryApplication {

    /**
     * Starts the Spring application context with the given command-line arguments.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(JmsMessageRollbackAndRedeliveryApplication.class, args);
    }
}

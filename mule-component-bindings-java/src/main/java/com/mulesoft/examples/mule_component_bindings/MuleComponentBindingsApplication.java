package com.mulesoft.examples.mule_component_bindings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the Spring Boot application that replaces the Mule application
 * {@code mule-component-bindings} ({@code config.resources=mule-component-bindings.xml}) and serves
 * its flows on the two configured listeners (D-011, D-106).
 *
 * <p>{@link ConfigurationPropertiesScan} registers the {@code @ConfigurationProperties} records of
 * this package and its subpackages, which bind the {@code twitter.*} and {@code stockstats.*} keys
 * (D-322).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MuleComponentBindingsApplication {

    /**
     * Starts the application context and its listeners.
     *
     * @param args command-line arguments, including {@code --key=value} property overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(MuleComponentBindingsApplication.class, args);
    }
}

package com.mulesoft.examples.munit_short_tutorial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;

/**
 * Starts the munit-short-tutorial-java Spring Boot application. The error-page auto-configuration
 * is excluded, so the context registers no error controller and exposes no {@code /error} route
 * (D-062, D-477).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
public class MunitShortTutorialApplication {

    public static void main(String[] args) {
        SpringApplication.run(MunitShortTutorialApplication.class, args);
    }
}

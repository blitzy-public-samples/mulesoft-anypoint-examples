package com.mulesoft.examples.http_request_response_with_logger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;

/**
 * Starts the http-request-response-with-logger Spring Boot application. No error controller or
 * error page is registered, and {@code /error} is an ordinary path (D-108).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
public class HttpRequestResponseWithLoggerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HttpRequestResponseWithLoggerApplication.class, args);
    }
}

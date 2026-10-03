/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the Spring Boot application that proxies every HTTP request on {@code http.port} to the
 * Box API (flow {@code rest-api-proxy}) on embedded Undertow (D-010). The context registers no
 * error controller and no {@code /error} mapping, {@code /error} reaches the proxy like every other
 * path (D-314), and the properties scan binds every {@code @ConfigurationProperties} type in this
 * package and its subpackages (D-003).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ProxyingARestApiApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ProxyingARestApiApplication.class, args);
    }
}

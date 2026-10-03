/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point of the SOAP proxy example: serves flow {@code main} of
 * {@code proxying-a-soap-api/src/main/app/soap-api-proxy.xml}, the SOAP envelope pass-through to
 * the upstream ShopService (D-059), on embedded Undertow (D-010). The configuration-properties scan
 * binds the {@code config} records {@code ShopServiceRequestProperties}
 * ({@code request.http-request-configuration}) and {@code ProxyServiceProperties}
 * ({@code cxf.proxy-service}) (D-543).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProxyingASoapApiApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments, passed unchanged to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ProxyingASoapApiApplication.class, args);
    }
}

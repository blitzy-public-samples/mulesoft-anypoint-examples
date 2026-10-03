package com.mulesoft.examples.get_customer_list_from_netsuite.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Enables the annotated test class only when {@code application-it.yml} on the test classpath holds every
 * key listed in {@link #keys()} with a value that is neither blank nor {@code TODO}; otherwise the class is
 * disabled by {@link ItCredentialsCondition} before any test instance or Spring application context is
 * created (D-021).
 *
 * <p>Usage on a live-sandbox integration test:
 * <pre>{@code
 * @EnabledIfItCredentials(keys = {"netsuite.account", "netsuite.oauth.client-id",
 *         "netsuite.oauth.certificate-id", "netsuite.oauth.private-key-path"})
 * class GetCustomerListFromNetsuiteIT { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * Dotted property names that {@code application-it.yml} must hold, each resolved through nested YAML
     * maps or as a flat dotted key (D-021).
     *
     * @return the required property names, checked in array order
     */
    String[] keys();
}

package com.mulesoft.examples.import_leads_into_salesforce.it.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Enables the annotated test class only when {@code application-it.yml} on the test classpath supplies
 * every key listed in {@link #keys()} with a value that is neither blank nor {@code TODO}; otherwise the
 * class is skipped before any extension builds an application context. See D-021.
 *
 * <p>The check is performed by {@link ItCredentialsCondition}, which JUnit registers through the
 * {@link ExtendWith} meta-annotation. Usage:
 *
 * <pre>{@code
 * @EnabledIfItCredentials(keys = {"sfdc.user", "sfdc.password", "sfdc.securityToken",
 *         "sfdc.key", "sfdc.secret", "sfdc.login-url"})
 * class ImportLeadsIntoSalesforceIT { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * Dotted property keys that {@code application-it.yml} must supply, in nested-map or flat dotted form.
     *
     * @return the required keys
     */
    String[] keys();
}

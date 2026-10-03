package com.mulesoft.examples.salesforce_data_retrieval.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Class-level credential gate for the Tier 2B live-sandbox tests (D-021).
 *
 * <p>The annotated test class is enabled only when {@code application-it.yml} exists on the test
 * classpath and every key listed in {@link #keys()} is present with a non-blank value other than
 * {@code TODO}. Otherwise JUnit reports the class as skipped before any Spring application context
 * starts. {@link ItCredentialsCondition} performs the evaluation.
 *
 * <p>Usage:
 * <pre>{@code
 * @EnabledIfItCredentials(keys = {"sfdc.username", "sfdc.password", "sfdc.securityToken",
 *         "sfdc.key", "sfdc.secret", "sfdc.login-url"})
 * class SalesforceIdRetrievalIT { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * Dotted property names that {@code application-it.yml} must hold, for example
     * {@code sfdc.username} (Salesforce key names per D-015). Nested YAML maps are flattened to these
     * names: {@code sfdc: {username: ...}} and {@code sfdc.username: ...} both satisfy
     * {@code sfdc.username}.
     *
     * @return the dotted property names, each of which must hold a non-blank value other than
     *         {@code TODO}
     */
    String[] keys();
}

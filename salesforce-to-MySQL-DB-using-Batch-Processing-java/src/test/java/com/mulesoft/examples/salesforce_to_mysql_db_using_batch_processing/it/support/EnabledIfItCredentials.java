package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs the annotated test class only when its live-sandbox credentials are configured (D-021).
 *
 * <p>The class runs when {@code application-it.yml} on the test classpath holds every key named in
 * {@link #keys()} with a value that is not blank and not {@code TODO}. Otherwise JUnit disables the
 * class through {@link ItCredentialsCondition} before any Spring application context starts, and the
 * test report lists the class as skipped with the reason the condition returns.
 *
 * <p>Example:
 * <pre>
 * &#64;EnabledIfItCredentials(keys = {"sfdc.user", "sfdc.password", "sfdc.securityToken",
 *         "sfdc.key", "sfdc.secret", "sfdc.login-url"})
 * &#64;ActiveProfiles("it")
 * &#64;SpringBootTest
 * class SalesforceToMySqlIT { }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * Dotted configuration keys the annotated class needs, for example {@code sfdc.user}.
     *
     * @return the keys that {@code application-it.yml} must hold, each with a value that is not blank
     *         and not {@code TODO}
     */
    String[] keys();
}

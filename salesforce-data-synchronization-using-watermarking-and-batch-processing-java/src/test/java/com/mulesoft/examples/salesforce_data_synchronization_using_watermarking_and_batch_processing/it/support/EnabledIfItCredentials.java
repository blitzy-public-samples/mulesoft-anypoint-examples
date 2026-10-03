package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Disables the annotated test class unless {@code application-it.yml} on the test classpath holds every
 * listed key with a value other than {@code TODO} (D-021).
 *
 * <p>{@link ItCredentialsCondition} performs the check when JUnit evaluates class-level conditions, before
 * JUnit creates a test instance or invokes any callback of the class; a disabled class starts no application
 * context. Example: {@code @EnabledIfItCredentials(keys = {"sfdc.user", "sfdc.login-url"})} on a test class
 * runs it only when both keys hold a value other than {@code TODO}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * Dotted configuration keys, for example {@code sfdc.login-url}, that must be present and not
     * {@code TODO}.
     *
     * @return the required keys of {@code application-it.yml}
     */
    String[] keys();
}
